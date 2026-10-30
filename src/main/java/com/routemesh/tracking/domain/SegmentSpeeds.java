package com.routemesh.tracking.domain;

/**
 * Learned effective speeds (straight-line km per hour of driving, so detours are absorbed) keyed by H3 cell and
 * hour of day. A port so the estimator can be unit tested and benchmarked without Postgres.
 */
public interface SegmentSpeeds {

    /** Learned speed for this cell and hour, or NaN when there is not enough data. */
    double kmh(long cell, int hourOfDay);

    /** Learned speed for the whole area at this hour, or NaN. */
    double areaKmh(int hourOfDay);
}
