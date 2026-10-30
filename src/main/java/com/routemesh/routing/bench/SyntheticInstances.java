package com.routemesh.routing.bench;

import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.geo.domain.Haversine;
import com.routemesh.routing.domain.Job;
import com.routemesh.routing.domain.Problem;
import com.routemesh.routing.domain.TravelModel;
import com.routemesh.routing.domain.Vehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Seeded city-scale instances in the spirit of the Solomon R / C / RC classes, but generated here (no benchmark
 * files are bundled). Same seed, same instance, on every JVM.
 */
public final class SyntheticInstances {

    public enum Layout { RANDOM, CLUSTERED, MIXED }

    /** Bengaluru-ish coordinates; the data is synthetic, the location only sets the latitude for distances. */
    public static final GeoPoint HUB = new GeoPoint(12.9716, 77.5946);
    public static final double SHIFT_START = 8 * 60;
    public static final double SHIFT_END = 20 * 60;

    private SyntheticInstances() {
    }

    public static Problem generate(long seed, int jobs, int vehicles, int capacityKg, Layout layout, double radiusKm,
                                   TravelModel travel) {
        var rnd = new SplittableRandom(seed);
        var centres = new ArrayList<GeoPoint>();
        for (int c = 0; c < 6; c++) {
            centres.add(Haversine.offset(HUB, radiusKm * (0.3 + 0.6 * rnd.nextDouble()), rnd.nextDouble() * 360));
        }
        var list = new ArrayList<Job>(jobs);
        for (int j = 0; j < jobs; j++) {
            boolean clustered = layout == Layout.CLUSTERED || (layout == Layout.MIXED && rnd.nextBoolean());
            GeoPoint at = clustered
                    ? Haversine.offset(centres.get(rnd.nextInt(centres.size())), Math.abs(rnd.nextGaussian()) * radiusKm * 0.08,
                    rnd.nextDouble() * 360)
                    : Haversine.offset(HUB, radiusKm * Math.sqrt(rnd.nextDouble()), rnd.nextDouble() * 360);
            double width = 60 + rnd.nextInt(5) * 30;          // 1h to 3h windows
            double open = SHIFT_START + 30 + rnd.nextDouble() * (SHIFT_END - SHIFT_START - 120 - width);
            int kg = 1 + rnd.nextInt(25);
            list.add(Job.delivery("J" + j, at, open, open + width, 4, kg));
        }
        var fleet = new ArrayList<Vehicle>(vehicles);
        for (int v = 0; v < vehicles; v++) {
            fleet.add(Vehicle.atDepot("V" + v, capacityKg, SHIFT_START, SHIFT_END));
        }
        return new Problem(HUB, fleet, list, travel);
    }

    public static List<Job> extraJobs(long seed, int count, double radiusKm, double notBefore) {
        var rnd = new SplittableRandom(seed ^ 0x5DEECE66DL);
        var list = new ArrayList<Job>(count);
        for (int j = 0; j < count; j++) {
            GeoPoint at = Haversine.offset(HUB, radiusKm * Math.sqrt(rnd.nextDouble()), rnd.nextDouble() * 360);
            double open = notBefore + rnd.nextDouble() * 240;
            list.add(Job.delivery("N" + j, at, open, open + 120, 4, 1 + rnd.nextInt(10)));
        }
        return list;
    }
}
