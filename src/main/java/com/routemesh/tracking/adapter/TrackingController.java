package com.routemesh.tracking.adapter;

import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.pod.application.PodService;
import com.routemesh.shared.error.ApiException;
import com.routemesh.shared.security.Caller;
import com.routemesh.shared.security.Device;
import com.routemesh.shipment.application.ShipmentService;
import com.routemesh.tracking.application.DeviceEventService;
import com.routemesh.tracking.application.DeviceEventService.Outcome;
import com.routemesh.tracking.application.PingPublisher;
import com.routemesh.tracking.application.PublicTracking;
import com.routemesh.tracking.application.RateLimiter;
import com.routemesh.tracking.application.TrackingEventStore.EventIn;
import com.routemesh.tracking.application.TrackingEventStore.Stored;
import com.routemesh.tracking.domain.Ping;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
class TrackingController {

    record Fix(Double lat, Double lng, Instant at) {
    }

    record PingBatch(List<Fix> pings) {
    }

    record EventBody(Long deviceSeq, UUID shipmentId, String status, String reason, Double lat, Double lng,
                     Instant at) {
    }

    record EventBatch(List<EventBody> events) {
    }

    record RescheduleIn(LocalDate date, LocalTime windowStart, LocalTime windowEnd) {
    }

    static final int MAX_PINGS = 500;
    private static final int PUBLIC_WRITES_PER_HOUR = 3;
    private static final int PUBLIC_READS_PER_MINUTE = 60;

    private final PingPublisher pings;
    private final DeviceEventService events;
    private final PodService pods;
    private final PublicTracking tracking;
    private final ShipmentService shipments;
    private final RateLimiter limiter;

    TrackingController(PingPublisher pings, DeviceEventService events, PodService pods, PublicTracking tracking,
                       ShipmentService shipments, RateLimiter limiter) {
        this.pings = pings;
        this.events = events;
        this.pods = pods;
        this.tracking = tracking;
        this.shipments = shipments;
        this.limiter = limiter;
    }

    // --- driver device

    /** Accepted means queued: filtering and geofencing happen in the consumer. */
    @PostMapping("/v1/drivers/{driverId}/pings:batch")
    ResponseEntity<Map<String, Object>> pings(Device device, @PathVariable UUID driverId, @RequestBody PingBatch in) {
        device.requireDriver(driverId);
        if (in.pings() == null || in.pings().isEmpty() || in.pings().size() > MAX_PINGS) {
            throw ApiException.invalid("send 1.." + MAX_PINGS + " pings");
        }
        var batch = new ArrayList<Ping>(in.pings().size());
        for (var f : in.pings()) {
            if (f == null || f.lat() == null || f.lng() == null || f.at() == null) {
                throw ApiException.invalid("every ping needs lat, lng and at");
            }
            try {
                batch.add(new Ping(driverId.toString(), f.at(), new GeoPoint(f.lat(), f.lng())));
            } catch (IllegalArgumentException e) {
                throw ApiException.invalid("bad-position", e.getMessage());
            }
        }
        pings.publish(driverId, batch);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("queued", batch.size()));
    }

    @PostMapping("/v1/drivers/{driverId}/events:batch")
    Map<String, List<Outcome>> events(Device device, @PathVariable UUID driverId, @RequestBody EventBatch in) {
        device.requireDriver(driverId);
        if (in.events() == null) {
            throw ApiException.invalid("events is required");
        }
        return Map.of("results", events.ingest(device, in.events().stream().map(TrackingController::event).toList()));
    }

    /** One event, same rules as the batch (the app uses this when it is online). */
    @PostMapping("/v1/shipments/{shipmentId}/events")
    Outcome event(Device device, @PathVariable UUID shipmentId, @RequestBody EventBody in) {
        var e = event(in);
        if (!shipmentId.equals(e.shipmentId())) {
            throw ApiException.invalid("shipmentId in the body must match the path");
        }
        return events.ingest(device, List.of(e)).getFirst();
    }

    @PostMapping("/v1/shipments/{shipmentId}/pod")
    PodService.PodView pod(Device device, @PathVariable UUID shipmentId, @RequestBody PodService.PodIn in) {
        return pods.submit(device, shipmentId, in);
    }

    // --- dispatcher

    @GetMapping("/v1/shipments/{shipmentId}/timeline")
    List<Stored> timeline(Caller caller, @PathVariable UUID shipmentId) {
        caller.requireDispatcher();
        return events.timeline(shipmentId);
    }

    // --- public, by tracking token

    @GetMapping("/v1/track/{token}")
    PublicTracking.TrackingPage track(@PathVariable String token) {
        limit("read:" + token, PUBLIC_READS_PER_MINUTE, Duration.ofMinutes(1));
        return tracking.page(token);
    }

    @PostMapping("/v1/track/{token}/reschedule")
    ShipmentService.ShipmentView reschedule(@PathVariable String token, @RequestBody RescheduleIn in) {
        limit("reschedule:" + token, PUBLIC_WRITES_PER_HOUR, Duration.ofHours(1));
        if (in.date() == null || in.windowStart() == null || in.windowEnd() == null) {
            throw ApiException.invalid("date, windowStart and windowEnd are required");
        }
        var v = shipments.reschedule(token, in.date(), in.windowStart(), in.windowEnd());
        // the customer must not see internal ids or another link; strip down to the slot
        return new ShipmentService.ShipmentView(null, v.reference(), v.kind(), v.status(), v.attempts(),
                v.serviceDate(), v.windowStart(), v.windowEnd(), v.weightKg(), null, null, v.version());
    }

    @PostMapping("/v1/track/{token}/otp:resend")
    ResponseEntity<Void> resend(@PathVariable String token) {
        limit("otp:" + token, PUBLIC_WRITES_PER_HOUR, Duration.ofHours(1));
        shipments.resendOtp(token);
        return ResponseEntity.accepted().build();
    }

    private void limit(String bucket, int n, Duration window) {
        if (token(bucket).length() > 100 || !limiter.allow(bucket, n, window)) {
            throw new ApiException(429, "rate-limited", "too many requests for this link, try again later");
        }
    }

    private static String token(String bucket) {
        return bucket.substring(bucket.indexOf(':') + 1);
    }

    private static EventIn event(EventBody b) {
        if (b == null || b.deviceSeq() == null) {
            throw ApiException.invalid("every event needs deviceSeq");
        }
        return new EventIn(b.deviceSeq(), b.shipmentId(), b.status(), b.reason(), b.lat(), b.lng(), b.at());
    }
}
