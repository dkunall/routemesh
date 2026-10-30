package com.routemesh.routing.domain;

import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.geo.domain.Haversine;
import com.routemesh.routing.bench.SyntheticInstances;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SolverTest {

    static final TravelModel TRAVEL = new TravelModel(1.3, 30);
    static final GeoPoint HUB = new GeoPoint(12.97, 77.59);

    /** Recomputes every schedule from scratch, without the solver's arrays, and fails on any violation. */
    static void assertFeasible(Problem p, Plan plan) {
        Map<String, Job> jobs = p.jobs().stream().collect(Collectors.toMap(Job::id, Function.identity()));
        var seen = new HashSet<String>();
        for (Plan.RoutePlan r : plan.routes()) {
            Vehicle v = p.vehicles().stream().filter(x -> x.id().equals(r.vehicleId())).findFirst().orElseThrow();
            GeoPoint at = v.start() == null ? p.depot() : v.start();
            double t = v.available();
            int load = v.carriedKg() + r.stops().stream().map(s -> jobs.get(s.jobId()))
                    .filter(j -> j.kind() == Job.Kind.DELIVERY).mapToInt(Job::demandKg).sum();
            assertThat(load).as("initial load of %s", v.id()).isLessThanOrEqualTo(v.capacityKg());
            for (Plan.PlannedStop s : r.stops()) {
                Job j = jobs.get(s.jobId());
                assertThat(seen.add(j.id())).as("%s visited once", j.id()).isTrue();
                t += TRAVEL.minutes(TRAVEL.km(at, j.at()));
                double start = Math.max(t, j.earliest());
                assertThat(start).as("start at %s", j.id()).isLessThanOrEqualTo(j.latest() + 1e-6);
                assertThat(s.start()).isCloseTo(start, org.assertj.core.data.Offset.offset(1e-6));
                load += j.kind() == Job.Kind.DELIVERY ? -j.demandKg() : j.demandKg();
                assertThat(load).isBetween(0, v.capacityKg());
                t = start + j.serviceMinutes();
                at = j.at();
            }
            t += TRAVEL.minutes(TRAVEL.km(at, p.depot()));
            assertThat(t).as("%s back by shift end", v.id()).isLessThanOrEqualTo(v.shiftEnd() + 1e-6);
        }
        seen.addAll(plan.unassigned());
        assertThat(seen).hasSize(p.jobs().size());
    }

    static Job job(String id, double kmEast, double kmNorth, double from, double to, int kg) {
        GeoPoint at = Haversine.offset(Haversine.offset(HUB, kmEast, 90), kmNorth, 0);
        return Job.delivery(id, at, from, to, 2, kg);
    }

    @ParameterizedTest
    @EnumSource(SyntheticInstances.Layout.class)
    void plansAreFeasibleOnSyntheticInstances(SyntheticInstances.Layout layout) {
        for (long seed = 1; seed <= 5; seed++) {
            Problem p = SyntheticInstances.generate(seed, 120, 25, 150, layout, 15, TRAVEL);
            assertFeasible(p, Solver.solve(p, Duration.ofSeconds(2)));
            assertFeasible(p, Solver.construct(p));
            assertFeasible(p, Solver.nearestNeighbour(p));
        }
    }

    @Test
    void localSearchNeverMakesThePlanLonger() {
        for (long seed = 1; seed <= 5; seed++) {
            Problem p = SyntheticInstances.generate(seed, 80, 20, 150, SyntheticInstances.Layout.MIXED, 12, TRAVEL);
            Plan built = Solver.construct(p);
            Plan improved = Solver.solve(p, Duration.ofSeconds(2));
            assertThat(improved.unassigned()).hasSameSizeAs(built.unassigned());
            assertThat(improved.totalKm()).isLessThanOrEqualTo(built.totalKm() + 1e-9);
        }
    }

    @Test
    void twoOptUncrossesARoute() {
        // four corners of a square visited in a crossing order with wide windows
        var jobs = List.of(job("a", 5, 5, 0, 1000, 1), job("b", -5, -5, 0, 1000, 1),
                job("c", -5, 5, 0, 1000, 1), job("d", 5, -5, 0, 1000, 1));
        var p = new Problem(HUB, List.of(Vehicle.atDepot("v", 100, 0, 1000)), jobs, TRAVEL);
        Plan crossing = Solver.evaluate(p, Map.of("v", List.of("a", "b", "c", "d")));
        Plan solved = Solver.solve(p, Duration.ofSeconds(1));
        assertThat(solved.totalKm()).isLessThan(crossing.totalKm());
        var order = solved.routes().getFirst().stops().stream().map(Plan.PlannedStop::jobId).toList();
        // the uncrossed tour goes around the square: a and b are never adjacent
        int ia = order.indexOf("a");
        int ib = order.indexOf("b");
        assertThat(Math.abs(ia - ib)).isEqualTo(2);
    }

    @Test
    void timeWindowsDecideTheOrderEvenWhenItCostsDistance() {
        // far stop must be served first, near stop later
        var jobs = List.of(job("near", 1, 0, 120, 130, 1), job("far", 10, 0, 0, 40, 1));
        var p = new Problem(HUB, List.of(Vehicle.atDepot("v", 100, 0, 600)), jobs, TRAVEL);
        Plan plan = Solver.solve(p, Duration.ofSeconds(1));
        assertFeasible(p, plan);
        assertThat(plan.routes().getFirst().stops()).extracting(Plan.PlannedStop::jobId).containsExactly("far", "near");
        assertThat(plan.routes().getFirst().stops().get(1).start()).isEqualTo(120.0); // waited for the window
    }

    @Test
    void anImpossibleWindowIsReportedNotDropped() {
        var jobs = List.of(job("ok", 1, 0, 0, 100, 1), job("too-far", 200, 0, 0, 10, 1));
        var p = new Problem(HUB, List.of(Vehicle.atDepot("v", 100, 0, 2000)), jobs, TRAVEL);
        Plan plan = Solver.solve(p, Duration.ofSeconds(1));
        assertThat(plan.unassigned()).containsExactly("too-far");
        assertThat(plan.assigned()).isEqualTo(1);
    }

    @Test
    void capacitySplitsLoadsAcrossVehicles() {
        var jobs = new ArrayList<Job>();
        for (int i = 0; i < 6; i++) {
            jobs.add(job("j" + i, i, 1, 0, 1000, 40));
        }
        var p = new Problem(HUB, List.of(Vehicle.atDepot("v1", 100, 0, 1000), Vehicle.atDepot("v2", 100, 0, 1000),
                Vehicle.atDepot("v3", 100, 0, 1000)), jobs, TRAVEL);
        Plan plan = Solver.solve(p, Duration.ofSeconds(1));
        assertFeasible(p, plan);
        assertThat(plan.unassigned()).isEmpty();
        assertThat(plan.vehiclesUsed()).isEqualTo(3);
    }

    @Test
    void pickupsAddLoadAfterTheStop() {
        // vehicle of 50 kg: a 40 kg delivery then a 40 kg pickup fits only in that order
        var drop = job("drop", 2, 0, 0, 1000, 40);
        var pick = new Job("pick", Job.Kind.PICKUP, Haversine.offset(HUB, 3, 90), 0, 1000, 2, 40);
        var p = new Problem(HUB, List.of(Vehicle.atDepot("v", 50, 0, 1000)), List.of(drop, pick), TRAVEL);
        Plan plan = Solver.solve(p, Duration.ofSeconds(1));
        assertFeasible(p, plan);
        assertThat(plan.routes().getFirst().stops()).extracting(Plan.PlannedStop::jobId).containsExactly("drop", "pick");
        assertThatThrownBy(() -> Solver.evaluate(p, Map.of("v", List.of("pick", "drop"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void incrementalInsertKeepsUntouchedRoutesAsTheyWere() {
        Problem p = SyntheticInstances.generate(7, 100, 20, 150, SyntheticInstances.Layout.MIXED, 15, TRAVEL);
        Plan plan = Solver.solve(p, Duration.ofSeconds(2));
        Map<String, List<String>> before = new HashMap<>();
        plan.routes().forEach(r -> before.put(r.vehicleId(), r.stops().stream().map(Plan.PlannedStop::jobId).toList()));
        Job extra = SyntheticInstances.extraJobs(7, 1, 15, 600).getFirst();
        var jobs = new ArrayList<>(p.jobs());
        jobs.add(extra);
        Problem grown = new Problem(p.depot(), p.vehicles(), jobs, TRAVEL);

        Plan after = Solver.insert(grown, before, List.of(extra.id()), Duration.ofMillis(200));

        assertFeasible(grown, after);
        assertThat(after.unassigned()).isEmpty();
        long changed = after.routes().stream().filter(r -> !before.get(r.vehicleId())
                .equals(r.stops().stream().map(Plan.PlannedStop::jobId).toList())).count();
        assertThat(changed).isEqualTo(1);
    }

    @Test
    void anOnRoadVehicleStartsFromWhereItIs() {
        GeoPoint here = Haversine.offset(HUB, 8, 90);
        var v = new Vehicle("v", 100, here, 600, 900, 0);
        var p = new Problem(HUB, List.of(v), List.of(job("x", 9, 0, 0, 2000, 1)), TRAVEL);
        Plan plan = Solver.solve(p, Duration.ofSeconds(1));
        double minutes = TRAVEL.minutes(TRAVEL.km(here, p.jobs().getFirst().at()));
        assertThat(plan.routes().getFirst().stops().getFirst().arrival()).isCloseTo(600 + minutes,
                org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void anExhaustedBudgetStillGivesAFeasiblePlan() {
        Problem p = SyntheticInstances.generate(3, 150, 25, 150, SyntheticInstances.Layout.RANDOM, 15, TRAVEL);
        Plan plan = Solver.solve(p, Duration.ZERO);
        assertThat(plan.budgetExhausted()).isTrue();
        assertFeasible(p, plan);
        assertThat(plan.assigned()).isPositive();
    }

    @Test
    void sameSeedSamePlan() {
        Problem a = SyntheticInstances.generate(11, 60, 10, 150, SyntheticInstances.Layout.CLUSTERED, 10, TRAVEL);
        Problem b = SyntheticInstances.generate(11, 60, 10, 150, SyntheticInstances.Layout.CLUSTERED, 10, TRAVEL);
        assertThat(Solver.construct(a)).isEqualTo(Solver.construct(b));
    }
}
