package com.routemesh.geo.domain;

/** Great-circle distance. A lower bound on road distance; the planner multiplies it by a road factor. */
public final class Haversine {

    public static final double EARTH_RADIUS_KM = 6371.0088;

    private Haversine() {
    }

    public static double km(GeoPoint a, GeoPoint b) {
        return km(a.lat(), a.lng(), b.lat(), b.lng());
    }

    public static double km(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /** A point {@code km} away from {@code from} on {@code bearingDeg}. Used by the synthetic data generators. */
    public static GeoPoint offset(GeoPoint from, double km, double bearingDeg) {
        double d = km / EARTH_RADIUS_KM;
        double b = Math.toRadians(bearingDeg);
        double lat1 = Math.toRadians(from.lat());
        double lng1 = Math.toRadians(from.lng());
        double lat2 = Math.asin(Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(b));
        double lng2 = lng1 + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(lat1), Math.cos(d) - Math.sin(lat1) * Math.sin(lat2));
        return new GeoPoint(Math.toDegrees(lat2), ((Math.toDegrees(lng2) + 540) % 360) - 180);
    }
}
