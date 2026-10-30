package com.routemesh.tracking.domain;

import com.routemesh.geo.domain.DouglasPeucker;
import com.routemesh.geo.domain.Haversine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/**
 * Cleans one driver's batch of pings: drops stale fixes, duplicates (same timestamp), fixes at or before the last
 * accepted one, and jumps that imply an impossible speed (GPS multipath near tall buildings does this). The
 * accepted fixes drive live position, geofences and speed learning; the simplified subset is what gets stored.
 */
public final class PingFilter {

    public record Result(List<Ping> accepted, List<Ping> stored, int duplicates, int stale, int outliers) {

        public Ping last() {
            return accepted.isEmpty() ? null : accepted.getLast();
        }
    }

    private final double maxSpeedKmh;
    private final double simplifyMeters;
    private final Duration maxAge;

    public PingFilter(double maxSpeedKmh, double simplifyMeters, Duration maxAge) {
        this.maxSpeedKmh = maxSpeedKmh;
        this.simplifyMeters = simplifyMeters;
        this.maxAge = maxAge;
    }

    /** @param previous the last accepted ping for this driver from an earlier batch, or null */
    public Result apply(List<Ping> batch, Ping previous, Instant now) {
        var byTime = new TreeMap<Instant, Ping>();
        int duplicates = 0;
        int stale = 0;
        for (var p : batch) {
            if (p.at().isBefore(now.minus(maxAge)) || (previous != null && !p.at().isAfter(previous.at()))) {
                stale++;
            } else if (byTime.putIfAbsent(p.at(), p) != null) {
                duplicates++;
            }
        }
        var accepted = new ArrayList<Ping>();
        int outliers = 0;
        Ping last = previous;
        for (var p : byTime.values()) {
            if (last != null && impliedKmh(last, p) > maxSpeedKmh) {
                outliers++;
                continue;
            }
            accepted.add(p);
            last = p;
        }
        var stored = DouglasPeucker.simplify(accepted, Ping::position, simplifyMeters);
        return new Result(List.copyOf(accepted), List.copyOf(stored), duplicates, stale, outliers);
    }

    static double impliedKmh(Ping a, Ping b) {
        double hours = Duration.between(a.at(), b.at()).toMillis() / 3_600_000.0;
        if (hours <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return Haversine.km(a.position(), b.position()) / hours;
    }

    public static Comparator<Ping> byTime() {
        return Comparator.comparing(Ping::at);
    }
}
