package com.routemesh.shipment.application;

import com.routemesh.outbox.application.Outbox;
import com.routemesh.shared.config.RouteMeshProperties;
import com.routemesh.shipment.domain.Otp;
import com.routemesh.shipment.domain.Shipment;
import com.routemesh.shipment.domain.ShipmentKind;
import com.routemesh.shipment.domain.ShipmentStatus;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.routemesh.shipment.domain.ShipmentStatus.*;

/**
 * The one place a shipment changes status. Callers pass a row they locked. Side effects of a change live here:
 * attempt counting, return-to-origin after the last attempt, rolling a failed delivery to the next day, a fresh
 * OTP whenever a delivery goes out, retiring the drop geofence, and the outbox event.
 */
@Component
public class ShipmentLifecycle {

    private final ShipmentStore store;
    private final Outbox outbox;
    private final OtpSender otpSender;
    private final Otp otp;
    private final RouteMeshProperties props;
    private final Clock clock;

    public ShipmentLifecycle(ShipmentStore store, Outbox outbox, OtpSender otpSender, RouteMeshProperties props,
                             Clock clock) {
        this.store = store;
        this.outbox = outbox;
        this.otpSender = otpSender;
        this.otp = new Otp(props.security().otpKey());
        this.props = props;
        this.clock = clock;
    }

    public Otp otp() {
        return otp;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Shipment move(Shipment s, ShipmentStatus next, String reason, String actor) {
        s.status().check(s.kind(), next);
        int attempts = next == FAILED_ATTEMPT ? s.attempts() + 1 : s.attempts();
        var moved = s.withStatus(next, attempts);
        if (next == FAILED_ATTEMPT) {
            var date = s.serviceDate().plusDays(1);
            moved = moved.withSlot(date, s.windowStart().plus(Duration.ofDays(1)), s.windowEnd().plus(Duration.ofDays(1)));
        }
        if (next == CREATED) {
            moved = moved.withRoute(null);
        }
        moved = save(moved);
        event(moved, s.status(), reason, actor);
        if (next == OUT_FOR_DELIVERY && s.kind() == ShipmentKind.DELIVERY) {
            String code = Otp.newCode();
            store.setOtp(s.id(), otp.hash(s.id(), code));
            otpSender.sendAfterCommit(moved, code);
        }
        if (next == FAILED_ATTEMPT && attempts >= props.delivery().maxAttempts()) {
            var last = s.kind() == ShipmentKind.DELIVERY ? RTO : CANCELLED;
            moved = save(moved.withStatus(last, attempts));
            event(moved, FAILED_ATTEMPT, "max-attempts", "system");
        }
        if (moved.status().terminal() || moved.status() == RTO) {
            store.deactivateFence(s.id());
        }
        return moved;
    }

    /** Saves a non-status change (route, slot) on a locked row. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Shipment save(Shipment s) {
        if (!store.update(s, clock.instant())) {
            throw new OptimisticLockingFailureException("shipment " + s.id() + " changed concurrently");
        }
        return reread(s);
    }

    private Shipment reread(Shipment s) {
        return store.find(s.id()).orElseThrow();
    }

    private void event(Shipment s, ShipmentStatus from, String reason, String actor) {
        var data = new LinkedHashMap<String, Object>();
        data.put("shipmentId", s.id().toString());
        data.put("from", from.name());
        data.put("status", s.status().name());
        data.put("attempts", s.attempts());
        data.put("actor", actor);
        if (reason != null) {
            data.put("reason", reason);
        }
        outbox.append(Outbox.SHIPMENT_EVENTS, s.id().toString(), ShipmentEvents.STATUS_CHANGED, Map.copyOf(data));
    }
}
