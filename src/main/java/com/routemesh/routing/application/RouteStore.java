package com.routemesh.routing.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RouteStore {

    record Stop(int seq, UUID shipmentId, Instant plannedEta, Instant predictedEta, Instant arrivedAt) {
    }

    /** Serialises planning per hub. */
    void lockHub(UUID hubId);

    List<UUID> vehiclesWithRoute(UUID hubId, LocalDate date);

    void insert(RouteRow r, Instant now);

    Optional<RouteRow> find(UUID id);

    List<RouteRow> forDate(UUID hubId, LocalDate date, RouteRow.Status status);

    Optional<RouteRow> activeForDriver(UUID driverId);

    /** Compare-and-set on version; false when someone else changed the route first. */
    boolean bump(UUID id, long expectedVersion, double km, Instant plannedReturn, Instant now);

    boolean dispatch(UUID id, long expectedVersion, UUID driverId, Instant now);

    void complete(UUID id, Instant now);

    List<Stop> stops(UUID routeId);

    void replaceStops(UUID routeId, List<UUID> shipments, List<Instant> plannedEtas);

    void predicted(UUID routeId, UUID shipmentId, Instant eta);

    /** Sets arrival once; returns true the first time. */
    boolean arrived(UUID shipmentId, Instant at);
}
