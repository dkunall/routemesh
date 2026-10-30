package com.routemesh.routing.domain;

import java.util.List;

/**
 * The solver's answer. Jobs it could not place feasibly are listed, never silently dropped. {@code budgetExhausted}
 * means the time budget ran out and the rest was finished greedily or local search stopped early.
 */
public record Plan(List<RoutePlan> routes, List<String> unassigned, double totalKm, boolean budgetExhausted) {

    public record RoutePlan(String vehicleId, List<PlannedStop> stops, double km, double returnAt) {
    }

    /** @param arrival when the vehicle gets there; @param start when service starts (after any waiting) */
    public record PlannedStop(String jobId, double arrival, double start, double departure) {
    }

    public long vehiclesUsed() {
        return routes.stream().filter(r -> !r.stops().isEmpty()).count();
    }

    public int assigned() {
        return routes.stream().mapToInt(r -> r.stops().size()).sum();
    }
}
