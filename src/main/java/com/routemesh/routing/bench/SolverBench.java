package com.routemesh.routing.bench;

import com.routemesh.routing.domain.Job;
import com.routemesh.routing.domain.Plan;
import com.routemesh.routing.domain.Problem;
import com.routemesh.routing.domain.Solver;
import com.routemesh.routing.domain.TravelModel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Compares the planner against a nearest-neighbour baseline on seeded synthetic instances, and an incremental
 * insert against a full re-solve. Prints Markdown. Usage: {@code SolverBench [seeds] [jobs,...] [budgetMs]}.
 */
public final class SolverBench {

    private static final TravelModel TRAVEL = new TravelModel(1.35, 22);

    public static void main(String[] args) {
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 10;
        int[] sizes = args.length > 1 ? Arrays.stream(args[1].split(",")).mapToInt(Integer::parseInt).toArray()
                : new int[]{100, 200};
        Duration budget = Duration.ofMillis(args.length > 2 ? Long.parseLong(args[2]) : 2000);
        var out = System.out;
        out.printf(Locale.ROOT, "# Solver benchmark%n%nJVM %s, %d cores. Travel: haversine x %.2f at %.0f km/h. "
                        + "Seeds 1..%d, local-search budget %d ms. Capacity 150 kg, 30 vehicles, 15 km radius.%n%n",
                Runtime.version(), Runtime.getRuntime().availableProcessors(), TRAVEL.roadFactor(), TRAVEL.kmh(),
                seeds, budget.toMillis());
        // warm-up so the first timed run is not the interpreter
        var warm = SyntheticInstances.generate(999, 100, 30, 150, SyntheticInstances.Layout.MIXED, 15, TRAVEL);
        for (int i = 0; i < 3; i++) {
            Solver.nearestNeighbour(warm);
            Solver.solve(warm, budget);
        }
        out.println("## Full plan: nearest neighbour (baseline) vs cheapest insertion vs cheapest insertion + local search");
        out.println();
        out.println("| jobs | layout | method | km (mean) | vehicles (mean) | unassigned (total) | ms (median) | km vs baseline |");
        out.println("|---:|---|---|---:|---:|---:|---:|---:|");
        for (int n : sizes) {
            for (var layout : SyntheticInstances.Layout.values()) {
                var rows = new HashMap<String, Agg>();
                for (int seed = 1; seed <= seeds; seed++) {
                    Problem p = SyntheticInstances.generate(seed, n, 30, 150, layout, 15, TRAVEL);
                    rows.computeIfAbsent("nearest-neighbour", k -> new Agg()).add(timed(() -> Solver.nearestNeighbour(p)));
                    rows.computeIfAbsent("cheapest-insertion", k -> new Agg()).add(timed(() -> Solver.construct(p)));
                    rows.computeIfAbsent("insertion+2opt+relocate", k -> new Agg()).add(timed(() -> Solver.solve(p, budget)));
                }
                double base = rows.get("nearest-neighbour").meanKm();
                for (String m : List.of("nearest-neighbour", "cheapest-insertion", "insertion+2opt+relocate")) {
                    Agg a = rows.get(m);
                    out.printf(Locale.ROOT, "| %d | %s | %s | %.1f | %.1f | %d | %.1f | %+.1f%% |%n", n, layout, m,
                            a.meanKm(), a.meanVehicles(), a.unassigned, a.medianMs(), (a.meanKm() / base - 1) * 100);
                }
            }
        }
        out.println();
        out.println("## Late orders: incremental insert vs full re-solve (10 orders added one at a time)");
        out.println();
        out.println("| jobs before | method | ms per order (median) | ms per order (p95) | final km (mean) | unassigned (total) |");
        out.println("|---:|---|---:|---:|---:|---:|");
        for (int n : sizes) {
            var inc = new Agg();
            var full = new Agg();
            for (int seed = 1; seed <= seeds; seed++) {
                Problem p = SyntheticInstances.generate(seed, n, 30, 150, SyntheticInstances.Layout.MIXED, 15, TRAVEL);
                Plan plan = Solver.solve(p, budget);
                Map<String, List<String>> current = sequences(plan);
                var jobs = new ArrayList<>(p.jobs());
                Plan incPlan = plan;
                Plan fullPlan = plan;
                for (Job extra : SyntheticInstances.extraJobs(seed, 10, 15, 9 * 60)) {
                    jobs.add(extra);
                    Problem grown = new Problem(p.depot(), p.vehicles(), jobs, TRAVEL);
                    var cur = current;
                    long t0 = System.nanoTime();
                    incPlan = Solver.insert(grown, cur, List.of(extra.id()), Duration.ofMillis(200));
                    inc.ms.add((System.nanoTime() - t0) / 1e6);
                    current = sequences(incPlan);
                    t0 = System.nanoTime();
                    fullPlan = Solver.solve(grown, budget);
                    full.ms.add((System.nanoTime() - t0) / 1e6);
                }
                inc.km.add(incPlan.totalKm());
                inc.unassigned += incPlan.unassigned().size();
                full.km.add(fullPlan.totalKm());
                full.unassigned += fullPlan.unassigned().size();
            }
            out.printf(Locale.ROOT, "| %d | incremental insert + 2-opt on the touched route | %.2f | %.2f | %.1f | %d |%n",
                    n, inc.medianMs(), inc.p95Ms(), inc.meanKm(), inc.unassigned);
            out.printf(Locale.ROOT, "| %d | full re-solve | %.2f | %.2f | %.1f | %d |%n",
                    n, full.medianMs(), full.p95Ms(), full.meanKm(), full.unassigned);
        }
    }

    static Map<String, List<String>> sequences(Plan plan) {
        var m = new HashMap<String, List<String>>();
        plan.routes().forEach(r -> m.put(r.vehicleId(), r.stops().stream().map(Plan.PlannedStop::jobId).toList()));
        return m;
    }

    private record Timed(Plan plan, double ms) {
    }

    private static Timed timed(Supplier<Plan> s) {
        long t0 = System.nanoTime();
        Plan p = s.get();
        return new Timed(p, (System.nanoTime() - t0) / 1e6);
    }

    private static final class Agg {
        final List<Double> km = new ArrayList<>();
        final List<Double> vehicles = new ArrayList<>();
        final List<Double> ms = new ArrayList<>();
        int unassigned;

        void add(Timed t) {
            km.add(t.plan().totalKm());
            vehicles.add((double) t.plan().vehiclesUsed());
            ms.add(t.ms());
            unassigned += t.plan().unassigned().size();
        }

        double meanKm() {
            return km.stream().mapToDouble(d -> d).average().orElse(0);
        }

        double meanVehicles() {
            return vehicles.stream().mapToDouble(d -> d).average().orElse(0);
        }

        double medianMs() {
            return pct(0.5);
        }

        double p95Ms() {
            return pct(0.95);
        }

        private double pct(double q) {
            double[] s = ms.stream().mapToDouble(d -> d).sorted().toArray();
            return s.length == 0 ? 0 : s[Math.min(s.length - 1, (int) Math.ceil(q * s.length) - 1)];
        }
    }
}
