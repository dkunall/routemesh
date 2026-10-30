package com.routemesh.fleet.domain;

import java.time.LocalTime;
import java.util.UUID;

/** A van or bike based at a hub, with the shift it can be planned within (hub local time). */
public record FleetVehicle(UUID id, UUID hubId, String code, int capacityKg, LocalTime shiftStart, LocalTime shiftEnd) {

    public FleetVehicle {
        if (capacityKg <= 0 || !shiftEnd.isAfter(shiftStart)) {
            throw new IllegalArgumentException("capacity must be positive and the shift must end after it starts");
        }
    }
}
