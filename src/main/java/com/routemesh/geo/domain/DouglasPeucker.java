package com.routemesh.geo.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Polyline simplification. Keeps the end points and every point further than {@code toleranceMeters} from the
 * chord of the stretch it sits in. Distances use a local equirectangular projection, which is fine at city scale.
 * Iterative, so a long track cannot overflow the stack.
 */
public final class DouglasPeucker {

    private DouglasPeucker() {
    }

    public static <T> List<T> simplify(List<T> points, Function<T, GeoPoint> at, double toleranceMeters) {
        int n = points.size();
        if (n < 3) {
            return List.copyOf(points);
        }
        double lat0 = Math.toRadians(at.apply(points.get(0)).lat());
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            GeoPoint p = at.apply(points.get(i));
            x[i] = Math.toRadians(p.lng()) * Math.cos(lat0) * Haversine.EARTH_RADIUS_KM * 1000;
            y[i] = Math.toRadians(p.lat()) * Haversine.EARTH_RADIUS_KM * 1000;
        }
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        var stack = new ArrayList<int[]>();
        stack.add(new int[]{0, n - 1});
        while (!stack.isEmpty()) {
            int[] span = stack.remove(stack.size() - 1);
            int a = span[0];
            int b = span[1];
            double best = -1;
            int idx = -1;
            for (int i = a + 1; i < b; i++) {
                double d = distanceToSegment(x[i], y[i], x[a], y[a], x[b], y[b]);
                if (d > best) {
                    best = d;
                    idx = i;
                }
            }
            if (idx >= 0 && best > toleranceMeters) {
                keep[idx] = true;
                stack.add(new int[]{a, idx});
                stack.add(new int[]{idx, b});
            }
        }
        var out = new ArrayList<T>();
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                out.add(points.get(i));
            }
        }
        return out;
    }

    static double distanceToSegment(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        double cx = ax + t * dx - px;
        double cy = ay + t * dy - py;
        return Math.sqrt(cx * cx + cy * cy);
    }
}
