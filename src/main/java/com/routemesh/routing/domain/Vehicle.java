package com.routemesh.routing.domain;

import com.routemesh.geo.domain.GeoPoint;

/**
 * A vehicle available for the plan. Routes end at the depot by {@code shiftEnd}. A vehicle still at the hub has
 * {@code start} null. One already on the road starts from its last position at {@code available}, carrying
 * {@code carriedKg} that belongs to no remaining stop (returns it already collected).
 */
public record Vehicle(String id, int capacityKg, GeoPoint start, double available, double shiftEnd, int carriedKg) {

    public static Vehicle atDepot(String id, int capacityKg, double shiftStart, double shiftEnd) {
        return new Vehicle(id, capacityKg, null, shiftStart, shiftEnd, 0);
    }
}
