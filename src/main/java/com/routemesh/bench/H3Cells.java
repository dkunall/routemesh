package com.routemesh.bench;

import com.routemesh.geo.domain.CellIndex;
import com.routemesh.geo.domain.GeoPoint;
import com.uber.h3core.H3Core;
import com.uber.h3core.LengthUnit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/** The same H3 calls the service makes, without Spring, so the benches run as plain mains. */
final class H3Cells implements CellIndex {

    private final H3Core h3;

    H3Cells() {
        try {
            h3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public long cell(GeoPoint p, int resolution) {
        return h3.latLngToCell(p.lat(), p.lng(), resolution);
    }

    @Override
    public List<Long> ring(long origin, int k) {
        return k == 0 ? List.of(origin) : h3.gridRing(origin, k);
    }

    @Override
    public GeoPoint center(long cell) {
        var c = h3.cellToLatLng(cell);
        return new GeoPoint(c.lat, c.lng);
    }

    @Override
    public double edgeKm(int resolution) {
        return h3.getHexagonEdgeLengthAvg(resolution, LengthUnit.km);
    }
}
