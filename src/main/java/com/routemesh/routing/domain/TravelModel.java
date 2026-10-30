package com.routemesh.routing.domain;

import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.geo.domain.Haversine;

/**
 * Straight-line distance times a road factor, at a constant planning speed. There is no road graph here; the
 * README says so. Satisfies the triangle inequality, which the solver's removal moves rely on.
 */
public record TravelModel(double roadFactor, double kmh) {

    public TravelModel {
        if (roadFactor < 1 || kmh <= 0) {
            throw new IllegalArgumentException("road factor must be >= 1 and speed > 0");
        }
    }

    public double km(GeoPoint a, GeoPoint b) {
        return Haversine.km(a, b) * roadFactor;
    }

    public double minutes(double km) {
        return km / kmh * 60.0;
    }
}
