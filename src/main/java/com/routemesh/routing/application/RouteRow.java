package com.routemesh.routing.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RouteRow(UUID id, UUID hubId, LocalDate serviceDate, UUID vehicleId, UUID driverId, Status status,
                       double km, Instant plannedReturn, long version) {

    /** PLANNED: vehicle still at the hub, stops can be reordered freely. DISPATCHED: on the road. */
    public enum Status {
        PLANNED,
        DISPATCHED,
        COMPLETED
    }
}
