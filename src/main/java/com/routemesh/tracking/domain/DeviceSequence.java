package com.routemesh.tracking.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;

/**
 * Orders a device's events by its own sequence numbers, never by its clock. An event applies only when every
 * lower number from that device has been applied; a hole is waited for, and skipped only after a grace period
 * (the device may have lost that event for good, e.g. app reinstall).
 */
public final class DeviceSequence {

    private DeviceSequence() {
    }

    /** The run of waiting sequence numbers that can apply now, given the last applied one. */
    public static List<Long> ready(long applied, NavigableSet<Long> waiting) {
        var out = new ArrayList<Long>();
        long next = applied + 1;
        for (long seq : waiting.tailSet(next, true)) {
            if (seq != next) {
                break;
            }
            out.add(seq);
            next++;
        }
        return out;
    }

    /** Where the cursor moves when the gap before {@code firstWaiting} is given up on. */
    public static long skipTo(long firstWaiting) {
        return firstWaiting - 1;
    }
}
