package com.routemesh.tracking.application;

import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.geo.domain.Ping;
import com.routemesh.tracking.domain.SegmentSpeeds;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistence ports of the tracking module, grouped because each is a handful of statements. */
public final class TrackingStores {

    private TrackingStores() {
    }

    public interface PingStore {
        /** Stores the simplified track; a repeated (driver, at) is ignored. Returns rows written. */
        int save(UUID driverId, List<Ping> pings);

        int purgeBefore(Instant cutoff);
    }

    /** Learned speeds. Cell 0 is not a valid H3 index, so it holds the area-wide figure per hour. */
    public interface SpeedStore extends SegmentSpeeds {
        long AREA = 0L;

        void record(long cell, int hour, double kmh, double alpha, Instant now);

        void refresh();
    }

    public record Fence(UUID id, String kind, UUID refId) {
    }

    public interface GeofenceStore {
        List<Fence> containing(GeoPoint p);

        Set<UUID> presence(UUID driverId);

        Optional<Fence> fence(UUID id);

        void enter(UUID driverId, UUID fenceId, Instant at);

        void exit(UUID driverId, UUID fenceId);
    }

    public interface AlertStore {
        /** False when this kind of alert already exists for the shipment. */
        boolean raise(String kind, UUID shipmentId, UUID routeId, String message, Instant now);
    }

    public record View(UUID shipmentId, String trackingToken, String reference, String status, Instant windowStart,
                       Instant windowEnd, Instant eta, int attempts, String driverName, UUID driverId,
                       Instant lastEventAt, Instant deliveredAt, Instant expiresAt) {
    }

    public interface ViewStore {
        /** Insert or overwrite, unless the stored row is from a later event. */
        void upsert(View v);

        void eta(UUID shipmentId, Instant eta);

        Optional<View> byToken(String token);

        int purgeExpired(Instant now);
    }
}
