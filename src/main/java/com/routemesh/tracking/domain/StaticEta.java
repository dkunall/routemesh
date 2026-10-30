package com.routemesh.tracking.domain;

import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.routing.domain.TravelModel;

/** Baseline: the planner's own model, straight line times road factor at one constant speed. */
public record StaticEta(TravelModel travel) implements EtaEstimator {

    @Override
    public double minutes(GeoPoint from, GeoPoint to, int hourOfDay) {
        return travel.minutes(travel.km(from, to));
    }

    @Override
    public String name() {
        return "static";
    }
}
