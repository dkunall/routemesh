package com.routemesh.shipment.application;

import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.idempotency.application.IdempotencyService;
import com.routemesh.idempotency.application.Idempotent;
import com.routemesh.outbox.application.Outbox;
import com.routemesh.shared.config.RouteMeshProperties;
import com.routemesh.shared.error.ApiException;
import com.routemesh.shared.security.Caller;
import com.routemesh.shipment.domain.Otp;
import com.routemesh.shipment.domain.Shipment;
import com.routemesh.shipment.domain.ShipmentKind;
import com.routemesh.shipment.domain.ShipmentStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Service
public class ShipmentService {

    public record NewShipment(UUID hubId, String reference, ShipmentKind kind, double lat, double lng, String address,
                              String recipientName, String recipientEmail, String recipientPhone,
                              LocalDate serviceDate, LocalTime windowStart, LocalTime windowEnd, int weightKg) {
    }

    /** What a shipper sees. The tracking token is the customer's link, so only the owner gets it. */
    public record ShipmentView(UUID id, String reference, ShipmentKind kind, ShipmentStatus status, int attempts,
                               LocalDate serviceDate, String windowStart, String windowEnd, int weightKg,
                               UUID routeId, String trackingToken, long version) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_OTP_FAILURES = 5;

    private final ShipmentStore store;
    private final ShipmentLifecycle lifecycle;
    private final HubLookup hubs;
    private final IdempotencyService idempotency;
    private final Outbox outbox;
    private final OtpSender otpSender;
    private final RouteMeshProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ShipmentService(ShipmentStore store, ShipmentLifecycle lifecycle, HubLookup hubs,
                           IdempotencyService idempotency, Outbox outbox, OtpSender otpSender,
                           RouteMeshProperties props, TransactionTemplate tx, Clock clock) {
        this.store = store;
        this.lifecycle = lifecycle;
        this.hubs = hubs;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.otpSender = otpSender;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    /** Port so this module does not reach into fleet's tables. */
    public interface HubLookup {
        boolean exists(UUID hubId);
    }

    public Idempotent<ShipmentView> create(Caller caller, String key, NewShipment req) {
        return idempotency.run("shipment.create", caller, key, req, ShipmentView.class,
                () -> tx.execute(t -> view(insert(caller, req))));
    }

    private Shipment insert(Caller caller, NewShipment r) {
        if (!hubs.exists(r.hubId())) {
            throw ApiException.invalid("unknown-hub", "hub not found");
        }
        if (r.weightKg() <= 0 || r.weightKg() > 1000) {
            throw ApiException.invalid("weightKg must be 1..1000");
        }
        GeoPoint drop;
        try {
            drop = new GeoPoint(r.lat(), r.lng());
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid(e.getMessage());
        }
        var zone = props.planning().zone();
        var today = LocalDate.now(clock.withZone(zone));
        if (r.serviceDate().isBefore(today) || r.serviceDate().isAfter(today.plusDays(14))) {
            throw ApiException.invalid("bad-date", "serviceDate must be within the next 14 days");
        }
        validateWindow(r.windowStart(), r.windowEnd());
        var now = clock.instant();
        var s = new Shipment(UUID.randomUUID(), r.hubId(), caller.subject(), r.reference(), r.kind(), drop,
                r.address(), r.recipientName(), r.recipientEmail(), r.recipientPhone(), r.serviceDate(),
                r.serviceDate().atTime(r.windowStart()).atZone(zone).toInstant(),
                r.serviceDate().atTime(r.windowEnd()).atZone(zone).toInstant(), r.weightKg(),
                ShipmentStatus.CREATED, 0, null, newToken(), 0, now);
        try {
            store.insert(s, props.delivery().fenceMeters());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("duplicate-reference", "reference " + r.reference() + " already exists");
        }
        outbox.append(Outbox.SHIPMENT_EVENTS, s.id().toString(), ShipmentEvents.CREATED,
                Map.of("shipmentId", s.id().toString(), "status", s.status().name(), "attempts", 0, "actor",
                        caller.subject()));
        return store.find(s.id()).orElseThrow();
    }

    private static void validateWindow(LocalTime start, LocalTime end) {
        if (!end.isAfter(start) || java.time.Duration.between(start, end).toMinutes() < 30) {
            throw ApiException.invalid("bad-window", "window must end at least 30 minutes after it starts");
        }
    }

    public ShipmentView get(Caller caller, UUID id) {
        var s = store.find(id).filter(x -> caller.canSee(x.shipperSubject()))
                .orElseThrow(() -> ApiException.notFound("shipment"));
        return view(s);
    }

    /** Dispatcher cancel; only before the parcel leaves the hub. */
    @Transactional
    public ShipmentView cancel(Caller caller, UUID id) {
        var s = locked(id, x -> caller.canSee(x.shipperSubject()));
        if (s.status() == ShipmentStatus.CANCELLED) {
            return view(s);
        }
        if (!s.status().canMoveTo(s.kind(), ShipmentStatus.CANCELLED)) {
            throw ApiException.conflict("too-late-to-cancel", "shipment is " + s.status());
        }
        return view(lifecycle.move(s, ShipmentStatus.CANCELLED, "cancelled", caller.subject()));
    }

    /**
     * Customer picks a new slot from the tracking link. Allowed before planning or after a failed attempt, for
     * tomorrow up to seven days ahead. A planned shipment has to be released by a dispatcher first.
     */
    @Transactional
    public ShipmentView reschedule(String token, LocalDate date, LocalTime start, LocalTime end) {
        var s = store.byToken(token).flatMap(x -> store.lock(x.id()))
                .orElseThrow(() -> ApiException.notFound("tracking link"));
        if (!s.status().plannable()) {
            throw ApiException.conflict("not-reschedulable", "this shipment can no longer be rescheduled online");
        }
        var zone = props.planning().zone();
        var today = LocalDate.now(clock.withZone(zone));
        if (!date.isAfter(today) || date.isAfter(today.plusDays(7))) {
            throw ApiException.invalid("bad-date", "pick a date from tomorrow up to seven days ahead");
        }
        validateWindow(start, end);
        var moved = lifecycle.save(s.withSlot(date, date.atTime(start).atZone(zone).toInstant(),
                date.atTime(end).atZone(zone).toInstant()));
        outbox.append(Outbox.SHIPMENT_EVENTS, s.id().toString(), ShipmentEvents.RESCHEDULED,
                Map.of("shipmentId", s.id().toString(), "status", moved.status().name(), "attempts", moved.attempts(),
                        "actor", "customer"));
        return view(moved);
    }

    /** New code for a delivery that is out; the old one stops working. */
    @Transactional
    public void resendOtp(String token) {
        var s = store.byToken(token).flatMap(x -> store.lock(x.id()))
                .orElseThrow(() -> ApiException.notFound("tracking link"));
        if (s.kind() != ShipmentKind.DELIVERY || s.status() != ShipmentStatus.OUT_FOR_DELIVERY) {
            throw ApiException.conflict("not-out-for-delivery", "a code is only sent while the parcel is on its way");
        }
        String code = Otp.newCode();
        store.setOtp(s.id(), lifecycle.otp().hash(s.id(), code));
        otpSender.sendAfterCommit(s, code);
    }

    /** Checks a delivery code inside the caller's transaction; locks the code after five wrong tries. */
    public boolean checkOtp(Shipment s, String code) {
        var hash = store.otpHash(s.id()).orElse(null);
        if (hash == null) {
            return false;
        }
        if (lifecycle.otp().matches(s.id(), code, hash)) {
            return true;
        }
        if (store.otpFailed(s.id()) >= MAX_OTP_FAILURES) {
            store.setOtp(s.id(), null);
        }
        return false;
    }

    private Shipment locked(UUID id, Function<Shipment, Boolean> visible) {
        return store.lock(id).filter(visible::apply).orElseThrow(() -> ApiException.notFound("shipment"));
    }

    ShipmentView view(Shipment s) {
        var zone = props.planning().zone();
        return new ShipmentView(s.id(), s.reference(), s.kind(), s.status(), s.attempts(), s.serviceDate(),
                s.windowStart().atZone(zone).toLocalTime().toString(), s.windowEnd().atZone(zone).toLocalTime().toString(),
                s.weightKg(), s.routeId(), s.trackingToken(), s.version());
    }

    private static String newToken() {
        byte[] raw = new byte[18];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
