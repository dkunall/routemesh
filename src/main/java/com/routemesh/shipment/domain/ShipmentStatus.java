package com.routemesh.shipment.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;

/**
 * Delivery lifecycle. Deliveries go hub -> customer; returns go customer -> hub and skip the hub-side states.
 * Every change goes through {@link #check}, so an out-of-order or replayed device event cannot jump states.
 */
public enum ShipmentStatus {
    CREATED,
    ASSIGNED,
    PICKED_UP,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    FAILED_ATTEMPT,
    RTO,
    RETURNED,
    CANCELLED;

    private static final Map<ShipmentStatus, Set<ShipmentStatus>> DELIVERY = Map.ofEntries(
            entry(CREATED, EnumSet.of(ASSIGNED, CANCELLED)),
            entry(ASSIGNED, EnumSet.of(PICKED_UP, CREATED, CANCELLED)),
            entry(PICKED_UP, EnumSet.of(IN_TRANSIT)),
            entry(IN_TRANSIT, EnumSet.of(OUT_FOR_DELIVERY)),
            entry(OUT_FOR_DELIVERY, EnumSet.of(DELIVERED, FAILED_ATTEMPT)),
            entry(FAILED_ATTEMPT, EnumSet.of(OUT_FOR_DELIVERY, ASSIGNED, RTO)),
            entry(RTO, EnumSet.of(RETURNED)));

    /** A return: ASSIGNED -> OUT_FOR_DELIVERY (driver heading there) -> PICKED_UP (collected) -> DELIVERED (at hub). */
    private static final Map<ShipmentStatus, Set<ShipmentStatus>> RETURN = Map.ofEntries(
            entry(CREATED, EnumSet.of(ASSIGNED, CANCELLED)),
            entry(ASSIGNED, EnumSet.of(OUT_FOR_DELIVERY, CREATED, CANCELLED)),
            entry(OUT_FOR_DELIVERY, EnumSet.of(PICKED_UP, FAILED_ATTEMPT)),
            entry(PICKED_UP, EnumSet.of(DELIVERED)),
            entry(FAILED_ATTEMPT, EnumSet.of(OUT_FOR_DELIVERY, ASSIGNED, CANCELLED)));

    public boolean canMoveTo(ShipmentKind kind, ShipmentStatus next) {
        var table = kind == ShipmentKind.RETURN ? RETURN : DELIVERY;
        return table.getOrDefault(this, Set.of()).contains(next);
    }

    public void check(ShipmentKind kind, ShipmentStatus next) {
        if (!canMoveTo(kind, next)) {
            throw new IllegalTransition(kind, this, next);
        }
    }

    public boolean terminal() {
        return this == DELIVERED || this == RETURNED || this == CANCELLED;
    }

    /** Still to be visited by a vehicle, so it belongs in a route plan. */
    public boolean plannable() {
        return this == CREATED || this == FAILED_ATTEMPT;
    }

    public static final class IllegalTransition extends com.routemesh.shared.domain.DomainConflictException {
        public IllegalTransition(ShipmentKind kind, ShipmentStatus from, ShipmentStatus to) {
            super("illegal-transition", kind + " shipment cannot go from " + from + " to " + to);
        }
    }
}
