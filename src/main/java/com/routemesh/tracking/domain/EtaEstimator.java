package com.routemesh.tracking.domain;

import com.routemesh.geo.domain.GeoPoint;

/** Minutes of driving from one point to another, starting at a given hour of day. */
public interface EtaEstimator {

    double minutes(GeoPoint from, GeoPoint to, int hourOfDay);

    String name();
}
