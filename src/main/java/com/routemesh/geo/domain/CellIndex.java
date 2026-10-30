package com.routemesh.geo.domain;

import java.util.List;

/** Hexagonal cells (H3 in production). Kept as a port so the domain does not load native code in unit tests. */
public interface CellIndex {

    long cell(GeoPoint p, int resolution);

    /** Cells at grid distance exactly {@code k} from {@code origin} (k = 0 is the origin itself). */
    List<Long> ring(long origin, int k);

    GeoPoint center(long cell);

    /** Approximate edge length of a cell at this resolution, in km. */
    double edgeKm(int resolution);
}
