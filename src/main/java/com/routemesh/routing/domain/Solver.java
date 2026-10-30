package com.routemesh.routing.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * VRP with time windows, capacity, mixed deliveries and pickups. Heuristics only: cheapest-insertion construction,
 * then 2-opt within routes and relocate between routes until no move improves or the time budget is spent. The
 * nearest-neighbour builder is kept as the baseline the benchmark compares against.
 */
public final class Solver {

    private static final double EPS = 1e-7;

    private Solver() {
    }

    /** Builds from scratch: cheapest insertion, then local search on every route. */
    public static Plan solve(Problem p, Duration budget) {
        long deadline = deadline(budget);
        var in = new Instance(p);
        var routes = emptyRoutes(in);
        var pending = new LinkedHashSet<Integer>();
        for (int j = 0; j < in.jobs; j++) {
            pending.add(in.jobNode(j));
        }
        boolean exhausted = construct(routes, pending, deadline);
        exhausted |= localSearch(routes, null, deadline);
        return toPlan(in, routes, pending, exhausted);
    }

    /** Cheapest insertion only, no local search. Used to show what the local search adds. */
    public static Plan construct(Problem p) {
        var in = new Instance(p);
        var routes = emptyRoutes(in);
        var pending = new LinkedHashSet<Integer>();
        for (int j = 0; j < in.jobs; j++) {
            pending.add(in.jobNode(j));
        }
        construct(routes, pending, Long.MAX_VALUE);
        return toPlan(in, routes, pending, false);
    }

    /**
     * Keeps the current sequences and inserts {@code newJobs} by cheapest insertion; then 2-opt on the routes that
     * changed only. This is what runs when an order arrives after planning.
     *
     * @param current vehicle id to job ids in visiting order; every job must be in {@code p.jobs()}
     */
    public static Plan insert(Problem p, Map<String, List<String>> current, Collection<String> newJobs,
                              Duration budget) {
        long deadline = deadline(budget);
        var in = new Instance(p);
        var routes = emptyRoutes(in);
        var nodeOf = nodesById(in);
        for (int v = 0; v < in.vehicles; v++) {
            var ids = current.getOrDefault(in.vehicle(v).id(), List.of());
            routes.get(v).set(ids.stream().mapToInt(id -> require(nodeOf, id)).toArray());
        }
        var pending = new LinkedHashSet<Integer>();
        newJobs.forEach(id -> pending.add(require(nodeOf, id)));
        var before = new int[in.vehicles][];
        for (int v = 0; v < in.vehicles; v++) {
            before[v] = routes.get(v).seq;
        }
        boolean exhausted = construct(routes, pending, deadline);
        var touched = new java.util.HashSet<Integer>();
        for (int v = 0; v < in.vehicles; v++) {
            if (routes.get(v).seq != before[v]) {
                touched.add(v);
            }
        }
        exhausted |= localSearch(routes, touched, deadline);
        return toPlan(in, routes, pending, exhausted);
    }

    /** Baseline: each vehicle in turn drives to the nearest feasible job until none fits, then the next vehicle. */
    public static Plan nearestNeighbour(Problem p) {
        var in = new Instance(p);
        var routes = emptyRoutes(in);
        var pending = new LinkedHashSet<Integer>();
        for (int j = 0; j < in.jobs; j++) {
            pending.add(in.jobNode(j));
        }
        for (RouteState r : routes) {
            while (true) {
                int last = r.size() == 0 ? in.startNode(r.vehicle) : r.seq[r.size() - 1];
                int best = -1;
                double bestKm = Double.POSITIVE_INFINITY;
                for (int node : pending) {
                    if (!Double.isNaN(r.insertionCost(node, r.size())) && in.km[last][node] < bestKm) {
                        bestKm = in.km[last][node];
                        best = node;
                    }
                }
                if (best < 0) {
                    break;
                }
                r.insert(best, r.size());
                pending.remove(best);
            }
        }
        return toPlan(in, routes, pending, false);
    }

    /** Checks a given assignment (e.g. one loaded from the database) and computes its schedule. */
    public static Plan evaluate(Problem p, Map<String, List<String>> sequences) {
        var in = new Instance(p);
        var routes = emptyRoutes(in);
        var nodeOf = nodesById(in);
        for (int v = 0; v < in.vehicles; v++) {
            var ids = sequences.getOrDefault(in.vehicle(v).id(), List.of());
            routes.get(v).set(ids.stream().mapToInt(id -> require(nodeOf, id)).toArray());
            if (!routes.get(v).feasible()) {
                throw new IllegalArgumentException("route for " + in.vehicle(v).id() + " is infeasible");
            }
        }
        return toPlan(in, routes, Set.of(), false);
    }

    // --- construction ------------------------------------------------------------------------------------------

    /**
     * Repeatedly inserts the pending job whose cheapest feasible insertion is cheapest overall. Best insertion per
     * (job, route) is cached and recomputed only for the route that changed. If the deadline passes, the rest are
     * appended greedily to whichever route end is nearest and feasible.
     */
    private static boolean construct(List<RouteState> routes, Set<Integer> pending, long deadline) {
        if (pending.isEmpty()) {
            return false;
        }
        var jobs = new ArrayList<>(pending);
        int m = routes.size();
        double[][] cost = new double[jobs.size()][m];
        int[][] pos = new int[jobs.size()][m];
        for (int j = 0; j < jobs.size(); j++) {
            for (int r = 0; r < m; r++) {
                best(routes.get(r), jobs.get(j), cost[j], pos[j], r);
            }
        }
        boolean[] done = new boolean[jobs.size()];
        while (true) {
            if (System.nanoTime() > deadline) {
                greedyFinish(routes, pending);
                return true;
            }
            int bj = -1;
            int br = -1;
            double bc = Double.POSITIVE_INFINITY;
            for (int j = 0; j < jobs.size(); j++) {
                if (done[j]) {
                    continue;
                }
                for (int r = 0; r < m; r++) {
                    if (cost[j][r] < bc) {
                        bc = cost[j][r];
                        bj = j;
                        br = r;
                    }
                }
            }
            if (bj < 0) {
                return false;
            }
            RouteState route = routes.get(br);
            route.insert(jobs.get(bj), pos[bj][br]);
            done[bj] = true;
            pending.remove(jobs.get(bj));
            for (int j = 0; j < jobs.size(); j++) {
                if (!done[j]) {
                    best(route, jobs.get(j), cost[j], pos[j], br);
                }
            }
        }
    }

    private static void best(RouteState route, int node, double[] cost, int[] pos, int r) {
        cost[r] = Double.POSITIVE_INFINITY;
        for (int p = 0; p <= route.size(); p++) {
            double c = route.insertionCost(node, p);
            if (!Double.isNaN(c) && c < cost[r]) {
                cost[r] = c;
                pos[r] = p;
            }
        }
    }

    private static void greedyFinish(List<RouteState> routes, Set<Integer> pending) {
        for (var it = pending.iterator(); it.hasNext(); ) {
            int node = it.next();
            RouteState target = null;
            double bestKm = Double.POSITIVE_INFINITY;
            for (RouteState r : routes) {
                double c = r.insertionCost(node, r.size());
                if (!Double.isNaN(c) && c < bestKm) {
                    bestKm = c;
                    target = r;
                }
            }
            if (target != null) {
                target.insert(node, target.size());
                it.remove();
            }
        }
    }

    // --- local search ------------------------------------------------------------------------------------------

    /** @return true if the deadline stopped it before a local optimum */
    private static boolean localSearch(List<RouteState> routes, Set<Integer> only, long deadline) {
        boolean improved = true;
        while (improved) {
            improved = false;
            for (RouteState r : routes) {
                if (only != null && !only.contains(r.vehicle)) {
                    continue;
                }
                if (System.nanoTime() > deadline) {
                    return true;
                }
                improved |= twoOpt(r, deadline);
            }
            if (only == null) {
                if (System.nanoTime() > deadline) {
                    return true;
                }
                improved |= relocate(routes, deadline);
            }
        }
        return false;
    }

    /** Reverses a stretch of one route when that shortens it and every window still holds. First improvement. */
    static boolean twoOpt(RouteState r, long deadline) {
        boolean any = false;
        boolean improved = true;
        var in = r.in;
        while (improved && System.nanoTime() < deadline) {
            improved = false;
            int n = r.size();
            outer:
            for (int i = 0; i < n - 1; i++) {
                int a = i == 0 ? in.startNode(r.vehicle) : r.seq[i - 1];
                for (int k = i + 1; k < n; k++) {
                    int b = r.seq[i];
                    int c = r.seq[k];
                    int d = k == n - 1 ? 0 : r.seq[k + 1];
                    double delta = in.km[a][c] + in.km[b][d] - in.km[a][b] - in.km[c][d];
                    if (delta < -EPS) {
                        int[] cand = r.seq.clone();
                        for (int x = i, y = k; x < y; x++, y--) {
                            int t = cand[x];
                            cand[x] = cand[y];
                            cand[y] = t;
                        }
                        int[] old = r.seq;
                        r.set(cand);
                        if (r.feasible()) {
                            improved = true;
                            any = true;
                            break outer;
                        }
                        r.set(old);
                    }
                }
            }
        }
        return any;
    }

    /** Moves one stop to another route where it costs less than it saves. Removal never breaks feasibility. */
    static boolean relocate(List<RouteState> routes, long deadline) {
        boolean any = false;
        for (RouteState from : routes) {
            for (int i = 0; i < from.size(); i++) {
                if (System.nanoTime() > deadline) {
                    return any;
                }
                int node = from.seq[i];
                double gain = from.removalGain(i);
                RouteState bestTo = null;
                int bestPos = -1;
                double bestDelta = -EPS;
                for (RouteState to : routes) {
                    if (to == from) {
                        continue;
                    }
                    for (int p = 0; p <= to.size(); p++) {
                        double c = to.insertionCost(node, p);
                        if (!Double.isNaN(c) && c - gain < bestDelta) {
                            bestDelta = c - gain;
                            bestTo = to;
                            bestPos = p;
                        }
                    }
                }
                if (bestTo != null) {
                    from.removeAt(i);
                    bestTo.insert(node, bestPos);
                    any = true;
                    i--;
                }
            }
        }
        return any;
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private static List<RouteState> emptyRoutes(Instance in) {
        var routes = new ArrayList<RouteState>(in.vehicles);
        for (int v = 0; v < in.vehicles; v++) {
            routes.add(new RouteState(in, v));
        }
        return routes;
    }

    private static Map<String, Integer> nodesById(Instance in) {
        var m = new HashMap<String, Integer>();
        for (int j = 0; j < in.jobs; j++) {
            if (m.put(in.problem.jobs().get(j).id(), in.jobNode(j)) != null) {
                throw new IllegalArgumentException("duplicate job id " + in.problem.jobs().get(j).id());
            }
        }
        return m;
    }

    private static int require(Map<String, Integer> nodeOf, String id) {
        Integer node = nodeOf.get(id);
        if (node == null) {
            throw new IllegalArgumentException("unknown job " + id);
        }
        return node;
    }

    private static long deadline(Duration budget) {
        long nanos = budget.toNanos();
        long now = System.nanoTime();
        return nanos > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + nanos;
    }

    private static Plan toPlan(Instance in, List<RouteState> routes, Set<Integer> pending, boolean exhausted) {
        var plans = routes.stream().map(RouteState::toPlan).toList();
        var unassigned = pending.stream().map(n -> in.problem.jobs().get(n - 1 - in.vehicles).id()).sorted().toList();
        // an unused vehicle reports start->depot km in its own RoutePlan but adds nothing to the total
        double km = 0;
        for (RouteState r : routes) {
            if (r.size() > 0) {
                km += r.km;
            }
        }
        return new Plan(plans, unassigned, km, exhausted);
    }
}
