package com.routemesh.tracking.domain;

import com.routemesh.geo.domain.CellIndex;
import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.geo.domain.Haversine;

/**
 * Walks the straight line in pieces about one cell long and charges each piece at the learned speed of the cell
 * it is in, for the hour the vehicle will be there. Falls back to the area speed for the hour, then to the static
 * model, when a cell has fewer than {@code minSamples} observations (that check lives in {@link SegmentSpeeds}).
 */
public final class LearnedEta implements EtaEstimator {

    private static final double MIN_KMH = 3;

    private final CellIndex cells;
    private final SegmentSpeeds speeds;
    private final StaticEta fallback;
    private final int resolution;

    public LearnedEta(CellIndex cells, SegmentSpeeds speeds, StaticEta fallback, int resolution) {
        this.cells = cells;
        this.speeds = speeds;
        this.fallback = fallback;
        this.resolution = resolution;
    }

    @Override
    public double minutes(GeoPoint from, GeoPoint to, int hourOfDay) {
        double km = Haversine.km(from, to);
        if (km < 1e-6) {
            return 0;
        }
        double step = Math.max(0.2, cells.edgeKm(resolution));
        int pieces = (int) Math.ceil(km / step);
        double pieceKm = km / pieces;
        double minutes = 0;
        for (int i = 0; i < pieces; i++) {
            double f = (i + 0.5) / pieces;
            var mid = new GeoPoint(from.lat() + (to.lat() - from.lat()) * f, from.lng() + (to.lng() - from.lng()) * f);
            int hour = Math.floorMod(hourOfDay + (int) (minutes / 60), 24);
            double kmh = speeds.kmh(cells.cell(mid, resolution), hour);
            if (Double.isNaN(kmh)) {
                kmh = speeds.areaKmh(hour);
            }
            if (Double.isNaN(kmh)) {
                minutes += fallback.minutes(from, to, hourOfDay) / pieces;
                continue;
            }
            minutes += pieceKm / Math.max(MIN_KMH, kmh) * 60;
        }
        return minutes;
    }

    @Override
    public String name() {
        return "learned";
    }
}
