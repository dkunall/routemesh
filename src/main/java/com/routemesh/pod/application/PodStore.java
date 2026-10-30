package com.routemesh.pod.application;

import com.routemesh.geo.domain.GeoPoint;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PodStore {

    record Pod(UUID shipmentId, UUID deviceId, String photoType, String photoSha256, double distanceMeters,
               Instant deliveredAt) {
    }

    /** Geodesic distance from the shipment's drop point, computed by PostGIS. */
    double distanceMeters(UUID shipmentId, GeoPoint p);

    void insert(Pod pod, byte[] photo, GeoPoint at);

    Optional<Pod> find(UUID shipmentId);
}
