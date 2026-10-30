package com.routemesh.tracking.domain;

import java.time.Duration;
import java.time.Instant;

/** A stop is at risk when its predicted arrival is later than the end of its window minus a safety margin. */
public final class SlaRisk {

    private SlaRisk() {
    }

    public static boolean atRisk(Instant predictedArrival, Instant windowEnd, Duration margin) {
        return predictedArrival.isAfter(windowEnd.minus(margin));
    }
}
