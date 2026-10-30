package com.routemesh.tracking.application;

import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.geo.domain.Haversine;
import com.routemesh.routing.application.RouteStore;
import com.routemesh.shared.config.RouteMeshProperties;
import com.routemesh.shared.error.ApiException;
import com.routemesh.shared.security.Device;
import com.routemesh.shipment.application.ShipmentLifecycle;
import com.routemesh.shipment.application.ShipmentStore;
import com.routemesh.shipment.domain.Shipment;
import com.routemesh.shipment.domain.ShipmentKind;
import com.routemesh.shipment.domain.ShipmentStatus;
import com.routemesh.tracking.application.TrackingEventStore.EventIn;
import com.routemesh.tracking.application.TrackingEventStore.Stored;
import com.routemesh.tracking.domain.DeviceSequence;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Status events from driver phones, which buffer offline and replay on reconnect. Each event carries the
 * device's own sequence number: (device, seq) is unique, so a replay is a no-op, and events apply strictly in
 * sequence order, so an upload that arrives out of order cannot move a shipment backwards. A missing number is
 * waited for {@code gapWait}, then skipped.
 */
@Service
public class DeviceEventService {

    public enum Result {
        APPLIED,
        REJECTED,
        DUPLICATE,
        WAITING_FOR_GAP
    }

    public record Outcome(long deviceSeq, Result result, String code) {
    }

    /** What a driver may set directly; DELIVERED for a delivery needs proof of delivery instead. */
    private static final Set<ShipmentStatus> DRIVER_SETTABLE = EnumSet.of(ShipmentStatus.PICKED_UP,
            ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.FAILED_ATTEMPT,
            ShipmentStatus.DELIVERED, ShipmentStatus.RETURNED);

    private static final int MAX_BATCH = 200;

    private final TrackingEventStore events;
    private final ShipmentStore shipments;
    private final RouteStore routes;
    private final ShipmentLifecycle lifecycle;
    private final RouteMeshProperties props;
    private final Clock clock;
    private final MeterRegistry meters;

    public DeviceEventService(TrackingEventStore events, ShipmentStore shipments, RouteStore routes,
                              ShipmentLifecycle lifecycle, RouteMeshProperties props, Clock clock,
                              MeterRegistry meters) {
        this.events = events;
        this.shipments = shipments;
        this.routes = routes;
        this.lifecycle = lifecycle;
        this.props = props;
        this.clock = clock;
        this.meters = meters;
    }

    @Transactional
    public List<Outcome> ingest(Device device, List<EventIn> batch) {
        if (batch.isEmpty() || batch.size() > MAX_BATCH) {
            throw ApiException.invalid("send 1.." + MAX_BATCH + " events");
        }
        long cursor = events.lockDevice(device.deviceId());
        var now = clock.instant();
        var late = new ArrayList<Long>();
        var out = new ArrayList<Outcome>();
        for (var e : batch) {
            if (e.deviceSeq() <= 0 || e.shipmentId() == null || e.status() == null) {
                throw ApiException.invalid("every event needs deviceSeq > 0, shipmentId and status");
            }
            String precheck = precheck(device, e);
            boolean inserted = events.insert(device.deviceId(), e, precheck == null ? "PENDING" : "REJECTED",
                    precheck, now);
            if (!inserted) {
                var existing = events.find(device.deviceId(), e.deviceSeq()).orElseThrow();
                boolean same = existing.shipmentId().equals(e.shipmentId()) && existing.status().equals(e.status())
                        && Objects.equals(existing.reason(), e.reason());
                out.add(new Outcome(e.deviceSeq(), same ? Result.DUPLICATE : Result.REJECTED,
                        same ? existing.state().toLowerCase() : "sequence-reused"));
                meters.counter("routemesh.device_events", "result", same ? "duplicate" : "seq_reused").increment();
                continue;
            }
            if (precheck != null) {
                out.add(new Outcome(e.deviceSeq(), Result.REJECTED, precheck));
                continue;
            }
            if (e.deviceSeq() <= cursor) {
                late.add(e.deviceSeq());
            }
            out.add(new Outcome(e.deviceSeq(), Result.WAITING_FOR_GAP, null));
        }
        for (long seq : late) {
            apply(device.deviceId(), events.find(device.deviceId(), seq).orElseThrow());
        }
        drain(device.deviceId(), cursor);
        var result = new ArrayList<Outcome>();
        for (var o : out) {
            if (o.result() == Result.WAITING_FOR_GAP) {
                var st = events.find(device.deviceId(), o.deviceSeq()).orElseThrow();
                result.add(switch (st.state()) {
                    case "APPLIED" -> new Outcome(o.deviceSeq(), Result.APPLIED, st.outcome());
                    case "REJECTED" -> new Outcome(o.deviceSeq(), Result.REJECTED, st.outcome());
                    default -> o;
                });
            } else {
                result.add(o);
            }
        }
        return result;
    }

    /** Gives up on a missing sequence number that has been missing for longer than {@code gapWait}. */
    @Transactional
    public int skipGap(UUID deviceId) {
        long cursor = events.lockDevice(deviceId);
        var waiting = events.after(deviceId, cursor);
        if (waiting.isEmpty()) {
            return 0;
        }
        var first = waiting.getFirst();
        if (first.deviceSeq() == cursor + 1
                || first.receivedAt().isAfter(clock.instant().minus(props.tracking().gapWait()))) {
            return 0;
        }
        long skipped = first.deviceSeq() - cursor - 1;
        long moved = DeviceSequence.skipTo(first.deviceSeq());
        events.setCursor(deviceId, moved);
        meters.counter("routemesh.device_events.gaps_skipped").increment(skipped);
        return drain(deviceId, moved);
    }

    public List<UUID> devicesWaiting() {
        return events.waitingSince(clock.instant().minus(props.tracking().gapWait()));
    }

    private int drain(UUID deviceId, long cursor) {
        var rows = events.after(deviceId, cursor);
        var bySeq = new java.util.HashMap<Long, Stored>();
        var seqs = new TreeSet<Long>();
        rows.forEach(r -> {
            bySeq.put(r.deviceSeq(), r);
            seqs.add(r.deviceSeq());
        });
        var ready = DeviceSequence.ready(cursor, seqs);
        for (long seq : ready) {
            var row = bySeq.get(seq);
            if (row.state().equals("PENDING")) {
                apply(deviceId, row);
            }
        }
        if (!ready.isEmpty()) {
            events.setCursor(deviceId, ready.getLast());
        }
        return ready.size();
    }

    private void apply(UUID deviceId, Stored e) {
        var now = clock.instant();
        var s = shipments.lock(e.shipmentId()).orElse(null);
        if (s == null) {
            events.outcome(e.id(), "REJECTED", "unknown-shipment", now);
            return;
        }
        var next = ShipmentStatus.valueOf(e.status());
        if (s.status() == next) {
            events.outcome(e.id(), "APPLIED", "already-" + next.name().toLowerCase(), now);
            return;
        }
        if (next == ShipmentStatus.FAILED_ATTEMPT) {
            String where = attemptLocation(s, e);
            if (where != null) {
                events.outcome(e.id(), "REJECTED", where, now);
                meters.counter("routemesh.device_events", "result", where).increment();
                return;
            }
        }
        // checked here rather than caught: an exception out of the lifecycle would mark the whole batch's
        // transaction rollback-only
        if (!s.status().canMoveTo(s.kind(), next)) {
            events.outcome(e.id(), "REJECTED", "illegal-transition", now);
            meters.counter("routemesh.device_events", "result", "illegal_transition").increment();
            return;
        }
        lifecycle.move(s, next, e.reason(), "device:" + deviceId);
        events.outcome(e.id(), "APPLIED", null, now);
        meters.counter("routemesh.device_events", "result", "applied").increment();
    }

    /** A failed attempt must be reported from near the address, so "customer not home" cannot be faked from afar. */
    private String attemptLocation(Shipment s, Stored e) {
        if (e.lat() == null || e.lng() == null) {
            return "position-required";
        }
        double m = Haversine.km(s.drop(), new GeoPoint(e.lat(), e.lng())) * 1000;
        return m > props.delivery().podRadiusMeters() * 2 ? "not-at-address" : null;
    }

    /** Checks that need no ordering: the status is one a driver may set, and the shipment is on their route. */
    private String precheck(Device device, EventIn e) {
        ShipmentStatus next;
        try {
            next = ShipmentStatus.valueOf(e.status());
        } catch (IllegalArgumentException ex) {
            return "unknown-status";
        }
        if (!DRIVER_SETTABLE.contains(next)) {
            return "not-driver-settable";
        }
        var s = shipments.find(e.shipmentId()).orElse(null);
        if (s == null || s.routeId() == null) {
            return "not-your-shipment";
        }
        var route = routes.find(s.routeId()).orElse(null);
        if (route == null || !device.driverId().equals(route.driverId())) {
            return "not-your-shipment";
        }
        if (next == ShipmentStatus.DELIVERED && s.kind() == ShipmentKind.DELIVERY) {
            return "pod-required";
        }
        if (e.lat() != null && e.lng() != null) {
            try {
                new GeoPoint(e.lat(), e.lng());
            } catch (IllegalArgumentException ex) {
                return "bad-position";
            }
        }
        return null;
    }

    public List<Stored> timeline(UUID shipmentId) {
        return events.forShipment(shipmentId);
    }
}
