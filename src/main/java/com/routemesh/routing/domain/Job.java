package com.routemesh.routing.domain;

import com.routemesh.geo.domain.GeoPoint;

/**
 * One delivery stop. Times are minutes from the start of the hub's planning day.
 *
 * @param earliest service may not start before this (the vehicle waits)
 * @param latest   service must start by this; arriving later makes the route infeasible
 */
public record Job(String id, Kind kind, GeoPoint at, double earliest, double latest, double serviceMinutes,
                  int demandKg) {

    /** A delivery is loaded at the hub and dropped at the stop; a pickup is collected at the stop and brought back. */
    public enum Kind {
        DELIVERY,
        PICKUP
    }

    public static Job delivery(String id, GeoPoint at, double earliest, double latest, double serviceMinutes, int kg) {
        return new Job(id, Kind.DELIVERY, at, earliest, latest, serviceMinutes, kg);
    }

    public Job {
        if (earliest > latest) {
            throw new IllegalArgumentException("window ends before it starts for " + id);
        }
        if (demandKg < 0 || serviceMinutes < 0) {
            throw new IllegalArgumentException("negative demand or service time for " + id);
        }
    }
}
