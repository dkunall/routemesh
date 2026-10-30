package com.routemesh.tracking.domain;

import com.routemesh.geo.domain.GeoPoint;

import java.time.Instant;

/** One GPS fix from a driver's phone. {@code at} is the device clock, used only within one driver's track. */
public record Ping(String driverId, Instant at, GeoPoint position) {
}
