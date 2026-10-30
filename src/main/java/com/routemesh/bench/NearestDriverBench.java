package com.routemesh.bench;

import com.routemesh.geo.adapter.H3CellIndex;
import com.routemesh.geo.domain.CellIndex;
import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.geo.domain.Haversine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Nearby-driver lookup: H3 cell buckets walked ring by ring (what the Redis index does, here in memory) against a
 * scan of every driver. Both answer "drivers within R km", R set just inside the k-ring disk; cell shapes vary, so the ring search
 * can miss a driver right at the edge, and the recall column reports how often.
 * Measures in-process lookup cost only; the service adds a Redis round trip per cell. Usage:
 * {@code NearestDriverBench [queries] [seed]}.
 */
public final class NearestDriverBench {

    private static final GeoPoint CENTRE = new GeoPoint(12.9716, 77.5946);
    private static final int RES = 9;
    private static final int K = 6;

    public static void main(String[] args) {
        int queries = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 11;
        var cells = new H3CellIndex();
        // neighbouring centres are about sqrt(3) * edge apart; take K of those, less one edge for a query that is not
        // at its cell centre. Not a hard guarantee: H3 cells vary in size and shape.
        double radius = (K * Math.sqrt(3) - 1) * cells.edgeKm(RES);
        var out = System.out;
        out.printf(Locale.ROOT, "# Nearby-driver lookup%n%nH3 res %d (edge %.3f km), k = %d rings, exact radius %.2f km, "
                        + "%d queries per row, drivers uniform in a 15 km disk, seed %d. JVM %s.%n%n",
                RES, cells.edgeKm(RES), K, radius, queries, seed, Runtime.version());
        out.println("| drivers | method | us per query (median) | us (p95) | distance checks per query (mean) | "
                + "found per query (mean) | recall vs scan |");
        out.println("|---:|---|---:|---:|---:|---:|---:|");
        for (int drivers : new int[]{1_000, 10_000, 50_000}) {
            var r = new Random(seed + drivers);
            var pos = new GeoPoint[drivers];
            var buckets = new HashMap<Long, List<Integer>>();
            for (int i = 0; i < drivers; i++) {
                pos[i] = around(r, 15);
                buckets.computeIfAbsent(cells.cell(pos[i], RES), c -> new ArrayList<>()).add(i);
            }
            var qs = new GeoPoint[queries];
            for (int q = 0; q < queries; q++) {
                qs[q] = around(r, 13);
            }
            for (int warm = 0; warm < 3; warm++) {
                for (var q : qs) {
                    scan(pos, q, radius);
                    rings(cells, buckets, pos, q, radius);
                }
            }
            var scanMs = new double[queries];
            var ringMs = new double[queries];
            long scanChecks = 0, ringChecks = 0, scanFound = 0, ringFound = 0, agree = 0;
            for (int q = 0; q < queries; q++) {
                long t0 = System.nanoTime();
                var exact = scan(pos, qs[q], radius);
                scanMs[q] = (System.nanoTime() - t0) / 1e3;
                t0 = System.nanoTime();
                var viaRings = rings(cells, buckets, pos, qs[q], radius);
                ringMs[q] = (System.nanoTime() - t0) / 1e3;
                scanChecks += drivers;
                ringChecks += viaRings.checks();
                scanFound += exact.size();
                ringFound += viaRings.found().size();
                var got = new HashSet<>(viaRings.found());
                agree += exact.stream().filter(got::contains).count();
            }
            double recall = scanFound == 0 ? 1 : (double) agree / scanFound;
            out.printf(Locale.ROOT, "| %d | scan all drivers | %.1f | %.1f | %.0f | %.2f | - |%n", drivers,
                    pct(scanMs, 0.5), pct(scanMs, 0.95), (double) scanChecks / queries, (double) scanFound / queries);
            out.printf(Locale.ROOT, "| %d | H3 k-ring buckets | %.1f | %.1f | %.0f | %.2f | %.4f |%n", drivers,
                    pct(ringMs, 0.5), pct(ringMs, 0.95), (double) ringChecks / queries,
                    (double) ringFound / queries, recall);
        }
    }

    private record RingResult(List<Integer> found, long checks) {
    }

    private static List<Integer> scan(GeoPoint[] pos, GeoPoint q, double radius) {
        var out = new ArrayList<Integer>();
        for (int i = 0; i < pos.length; i++) {
            if (Haversine.km(pos[i], q) <= radius) {
                out.add(i);
            }
        }
        return out;
    }

    private static RingResult rings(CellIndex cells, Map<Long, List<Integer>> buckets, GeoPoint[] pos, GeoPoint q,
                                    double radius) {
        long origin = cells.cell(q, RES);
        var out = new ArrayList<Integer>();
        long checks = 0;
        for (int k = 0; k <= K; k++) {
            for (long c : cells.ring(origin, k)) {
                var ids = buckets.get(c);
                if (ids == null) {
                    continue;
                }
                for (int i : ids) {
                    checks++;
                    if (Haversine.km(pos[i], q) <= radius) {
                        out.add(i);
                    }
                }
            }
        }
        return new RingResult(out, checks);
    }

    private static GeoPoint around(Random r, double km) {
        double d = km * Math.sqrt(r.nextDouble());
        double a = r.nextDouble() * 2 * Math.PI;
        return new GeoPoint(CENTRE.lat() + d * Math.cos(a) / 110.574,
                CENTRE.lng() + d * Math.sin(a) / (111.32 * Math.cos(Math.toRadians(CENTRE.lat()))));
    }

    private static double pct(double[] v, double q) {
        double[] s = v.clone();
        java.util.Arrays.sort(s);
        return s[Math.min(s.length - 1, (int) Math.ceil(q * s.length) - 1)];
    }
}
