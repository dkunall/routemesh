package com.routemesh.geo.domain;

/** WGS84 degrees. */
public record GeoPoint(double lat, double lng) {

    public GeoPoint {
        if (!(lat >= -90 && lat <= 90) || !(lng >= -180 && lng <= 180)) {
            throw new IllegalArgumentException("coordinates out of range: " + lat + "," + lng);
        }
    }
}
