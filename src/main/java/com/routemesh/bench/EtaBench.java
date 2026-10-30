package com.routemesh.bench;

import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.geo.domain.Haversine;
import com.routemesh.routing.domain.TravelModel;
import com.routemesh.tracking.domain.EtaEstimator;
import com.routemesh.tracking.domain.Ewma;
import com.routemesh.tracking.domain.LearnedEta;
import com.routemesh.tracking.domain.SegmentSpeeds;
import com.routemesh.tracking.domain.StaticEta;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Static vs learned ETA on a synthetic city. The "true" travel speed depends on distance from the centre, the hour
 * (two rush hours) and a coarse 2 km patchwork, with per-segment noise. Training trips feed the same EWMA rule the
 * ping pipeline uses (alpha 0.2, 3 samples before a cell is trusted, area-per-hour fallback); held-out trips are
 * scored. The world is built from the kind of structure the learned model can represent, so this shows the model
 * works as designed, not how it would do on real roads. Usage: {@code EtaBench [trainTrips] [testTrips] [seed]}.
 */
public final class EtaBench {

    private static final GeoPoint CENTRE = new GeoPoint(12.9716, 77.5946);
    private static final int RES = 9;
    private static final double ALPHA = 0.2;
    private static final long MIN_SAMPLES = 3;
    private static final double PING_SECONDS = 30;

    public static void main(String[] args) {
        int train = args.length > 0 ? Integer.parseInt(args[0]) : 4000;
        int test = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 7;
        var cells = new H3Cells();
        var world = new World(seed);

        var cellSpeeds = new HashMap<Key, Ewma>();
        var area = new Ewma[24];
        Arrays.fill(area, Ewma.EMPTY);
        var rnd = new Random(seed * 31 + 1);
        double sumKmh = 0;
        long n = 0;
        for (int t = 0; t < train; t++) {
            var trip = Trip.random(rnd);
            for (var seg : world.drive(trip, rnd)) {
                var key = new Key(cells.cell(seg.mid(), RES), seg.hour());
                cellSpeeds.merge(key, Ewma.EMPTY.add(seg.kmh(), ALPHA), (old, x) -> old.add(seg.kmh(), ALPHA));
                area[seg.hour()] = area[seg.hour()].add(seg.kmh(), ALPHA);
                sumKmh += seg.kmh();
                n++;
            }
        }
        double meanKmh = sumKmh / n;
        var speeds = new CountingSpeeds(cellSpeeds, area);
        var planner = new TravelModel(1.35, 22);
        // a fairer constant baseline: one speed fitted to the same training data (straight-line km/h)
        var fitted = new TravelModel(1.0, meanKmh);
        List<EtaEstimator> models = List.of(new StaticEta(planner), named(new StaticEta(fitted), "static-fitted"),
                new LearnedEta(cells, speeds, new StaticEta(planner), RES));

        var errors = new HashMap<String, Map<Boolean, List<Double>>>();
        var actualAll = new ArrayList<Double>();
        var testRnd = new Random(seed * 31 + 2);
        for (int t = 0; t < test; t++) {
            var trip = Trip.random(testRnd);
            double actual = world.drive(trip, testRnd).stream().mapToDouble(s -> s.minutes()).sum();
            actualAll.add(actual);
            boolean peak = World.peak(trip.hour());
            for (var m : models) {
                double predicted = m.minutes(trip.from(), trip.to(), trip.hour());
                errors.computeIfAbsent(m.name(), k -> new HashMap<>()).computeIfAbsent(peak, k -> new ArrayList<>())
                        .add(predicted - actual);
            }
        }

        var out = System.out;
        out.printf(Locale.ROOT, "# ETA benchmark (synthetic city)%n%n%d training trips (%d speed samples), %d held-out "
                        + "trips, seed %d. Trips 1-12 km, departures 07:00-21:59. Mean held-out trip %.1f min.%n"
                        + "H3 res %d cells with >= %d samples: %d of %d (cell, hour) keys; learned lookups that hit a "
                        + "cell %.1f%%, fell back to the area speed %.1f%%.%n%n",
                train, n, test, seed, actualAll.stream().mapToDouble(d -> d).average().orElse(0), RES, MIN_SAMPLES,
                cellSpeeds.values().stream().filter(e -> e.samples() >= MIN_SAMPLES).count(), cellSpeeds.size(),
                speeds.hitPct(), 100 - speeds.hitPct());
        out.println("| model | hours | trips | MAE min | p90 abs err min | mean bias min |");
        out.println("|---|---|---:|---:|---:|---:|");
        for (var m : models) {
            for (Boolean peak : List.of(true, false)) {
                var e = errors.get(m.name()).getOrDefault(peak, List.of());
                out.printf(Locale.ROOT, "| %s | %s | %d | %.2f | %.2f | %+.2f |%n", m.name(),
                        peak ? "rush (08-10, 17-20)" : "other", e.size(), mae(e), p90(e), mean(e));
            }
        }
        out.println();
        out.println("Static uses the planner's model (road factor 1.35 at 22 km/h);"
                + " static-fitted uses one straight-line speed fitted to the training samples ("
                + String.format(Locale.ROOT, "%.1f", meanKmh) + " km/h).");
    }

    private static EtaEstimator named(EtaEstimator e, String name) {
        return new EtaEstimator() {
            @Override
            public double minutes(GeoPoint from, GeoPoint to, int hourOfDay) {
                return e.minutes(from, to, hourOfDay);
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    private static double mae(List<Double> e) {
        return e.stream().mapToDouble(Math::abs).average().orElse(0);
    }

    private static double mean(List<Double> e) {
        return e.stream().mapToDouble(d -> d).average().orElse(0);
    }

    private static double p90(List<Double> e) {
        double[] s = e.stream().mapToDouble(Math::abs).sorted().toArray();
        return s.length == 0 ? 0 : s[Math.min(s.length - 1, (int) Math.ceil(0.9 * s.length) - 1)];
    }

    record Trip(GeoPoint from, GeoPoint to, int hour) {
        static Trip random(Random r) {
            var from = around(CENTRE, 14, r);
            GeoPoint to;
            do {
                to = around(from, 12, r);
            } while (Haversine.km(from, to) < 1 || Haversine.km(CENTRE, to) > 15);
            return new Trip(from, to, 7 + r.nextInt(15));
        }

        static GeoPoint around(GeoPoint c, double km, Random r) {
            double d = km * Math.sqrt(r.nextDouble());
            double a = r.nextDouble() * 2 * Math.PI;
            return new GeoPoint(c.lat() + d * Math.cos(a) / 110.574,
                    c.lng() + d * Math.sin(a) / (111.32 * Math.cos(Math.toRadians(c.lat()))));
        }
    }

    record Key(long cell, int hour) {
    }

    record Segment(GeoPoint mid, int hour, double kmh, double minutes) {
    }

    /** Ground truth speeds. Straight-line km/h, so detours are already folded in, as in the learned table. */
    static final class World {
        private final long seed;

        World(long seed) {
            this.seed = seed;
        }

        static boolean peak(int hour) {
            return (hour >= 8 && hour < 10) || (hour >= 17 && hour < 20);
        }

        double kmh(GeoPoint p, int hour) {
            double r = Haversine.km(CENTRE, p);
            double base = r < 4 ? 14 : r < 8 ? 19 : (r < 10 ? 27 : 22);
            double rush = peak(hour) ? (r < 8 ? 0.55 : 0.75) : (hour >= 21 ? 1.2 : 1.0);
            int gx = (int) Math.floor(p.lat() * 110.574 / 2), gy = (int) Math.floor(p.lng() * 108 / 2);
            double patch = 0.8 + 0.4 * new Random(seed * 1_000_003L + gx * 7919L + gy).nextDouble();
            return base * rush * patch;
        }

        /** Drive the straight line, one ping every 30 s, at the true speed with +/-20% per-segment noise. */
        List<Segment> drive(Trip t, Random r) {
            var out = new ArrayList<Segment>();
            var at = t.from();
            double minutes = 0;
            double total = Haversine.km(t.from(), t.to());
            double done = 0;
            while (done < total - 1e-9) {
                int hour = (t.hour() + (int) (minutes / 60)) % 24;
                double kmh = kmh(at, hour) * (0.8 + 0.4 * r.nextDouble());
                double step = Math.min(total - done, kmh * PING_SECONDS / 3600);
                double f = (done + step) / total;
                var next = new GeoPoint(t.from().lat() + (t.to().lat() - t.from().lat()) * f,
                        t.from().lng() + (t.to().lng() - t.from().lng()) * f);
                double segMin = step / kmh * 60;
                var mid = new GeoPoint((at.lat() + next.lat()) / 2, (at.lng() + next.lng()) / 2);
                out.add(new Segment(mid, hour, kmh, segMin));
                minutes += segMin;
                done += step;
                at = next;
            }
            return out;
        }
    }

    private static final class CountingSpeeds implements SegmentSpeeds {
        private final Map<Key, Ewma> cells;
        private final Ewma[] area;
        private long hits;
        private long lookups;

        CountingSpeeds(Map<Key, Ewma> cells, Ewma[] area) {
            this.cells = cells;
            this.area = area;
        }

        @Override
        public double kmh(long cell, int hourOfDay) {
            lookups++;
            var e = cells.get(new Key(cell, hourOfDay));
            if (e == null || e.samples() < MIN_SAMPLES) {
                return Double.NaN;
            }
            hits++;
            return e.value();
        }

        @Override
        public double areaKmh(int hourOfDay) {
            var e = area[hourOfDay];
            return e.samples() < MIN_SAMPLES ? Double.NaN : e.value();
        }

        double hitPct() {
            return lookups == 0 ? 0 : 100.0 * hits / lookups;
        }
    }
}
