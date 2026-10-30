package com.routemesh.geo.application;

import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.tracking.domain.Ping;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Live driver positions bucketed by H3 cell (Redis in production), for "who is near this pickup". */
public interface DriverIndex {

    record Nearby(UUID driverId, GeoPoint position, Instant at, int ring) {
    }

    void update(UUID driverId, Ping last);

    Optional<Ping> last(UUID driverId);

    /** Drivers whose last fix is within {@code k} rings of the point's cell and newer than {@code freshAfter}. */
    List<Nearby> near(GeoPoint p, int k, Instant freshAfter);
}
