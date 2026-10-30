package com.routemesh.routing.domain;

import java.util.Arrays;

/**
 * One vehicle's stop sequence with the arrays that make an insertion check O(1): service start times forward,
 * latest feasible service start backward, and load maxima on both sides of every gap. Rebuilt after each change
 * to this route only.
 */
final class RouteState {

    final Instance in;
    final int vehicle;
    int[] seq = new int[0];

    private double[] start = new double[0];
    private double[] latestStart = new double[0];
    private int[] loadAfter = new int[0];
    private int[] prefixMax = new int[1];
    private int[] suffixMax = new int[1];
    private int initialLoad;
    private double endLatest;
    private boolean feasible = true;
    double km;
    double returnAt;

    RouteState(Instance in, int vehicle) {
        this.in = in;
        this.vehicle = vehicle;
        rebuild();
    }

    RouteState copy() {
        var r = new RouteState(in, vehicle);
        r.set(seq.clone());
        return r;
    }

    boolean feasible() {
        return feasible;
    }

    int size() {
        return seq.length;
    }

    void set(int[] newSeq) {
        this.seq = newSeq;
        rebuild();
    }

    private int nodeAt(int pos) {
        return pos < 0 ? in.startNode(vehicle) : pos >= seq.length ? 0 : seq[pos];
    }

    private double departAt(int pos) {
        return pos < 0 ? in.vehicle(vehicle).available() : start[pos] + in.service[seq[pos]];
    }

    void rebuild() {
        int n = seq.length;
        Vehicle v = in.vehicle(vehicle);
        start = new double[n];
        latestStart = new double[n];
        loadAfter = new int[n];
        feasible = true;
        km = 0;
        int load = v.carriedKg();
        for (int node : seq) {
            if (in.kind[node] == Job.Kind.DELIVERY) {
                load += in.demand[node];
            }
        }
        initialLoad = load;
        double t = v.available();
        int prev = in.startNode(vehicle);
        for (int i = 0; i < n; i++) {
            int node = seq[i];
            km += in.km[prev][node];
            double arrive = t + in.minutes[prev][node];
            start[i] = Math.max(arrive, in.earliest[node]);
            if (start[i] > in.latest[node] + 1e-9) {
                feasible = false;
            }
            load += in.kind[node] == Job.Kind.DELIVERY ? -in.demand[node] : in.demand[node];
            loadAfter[i] = load;
            t = start[i] + in.service[node];
            prev = node;
        }
        km += in.km[prev][0];
        returnAt = t + in.minutes[prev][0];
        endLatest = v.shiftEnd();
        if (returnAt > endLatest + 1e-9) {
            feasible = false;
        }
        double next = endLatest;
        int nextNode = 0;
        for (int i = n - 1; i >= 0; i--) {
            int node = seq[i];
            latestStart[i] = Math.min(in.latest[node], next - in.minutes[node][nextNode] - in.service[node]);
            next = latestStart[i];
            nextNode = node;
        }
        prefixMax = new int[n + 1];
        prefixMax[0] = initialLoad;
        for (int i = 0; i < n; i++) {
            prefixMax[i + 1] = Math.max(prefixMax[i], loadAfter[i]);
        }
        suffixMax = new int[n + 1];
        suffixMax[n] = Integer.MIN_VALUE;
        for (int i = n - 1; i >= 0; i--) {
            suffixMax[i] = Math.max(suffixMax[i + 1], loadAfter[i]);
        }
        if (prefixMax[n] > v.capacityKg()) {
            feasible = false;
        }
    }

    /**
     * Extra km for putting {@code node} at position {@code pos} (before the stop now at pos), or NaN if that breaks
     * a time window, the shift end or capacity.
     */
    double insertionCost(int node, int pos) {
        int cap = in.vehicle(vehicle).capacityKg();
        int d = in.demand[node];
        if (in.kind[node] == Job.Kind.DELIVERY) {
            if (prefixMax[pos] + d > cap) {
                return Double.NaN;
            }
        } else {
            int before = pos == 0 ? initialLoad : loadAfter[pos - 1];
            if (before + d > cap || (pos < seq.length && suffixMax[pos] + d > cap)) {
                return Double.NaN;
            }
        }
        int prev = nodeAt(pos - 1);
        int next = nodeAt(pos);
        double arrive = departAt(pos - 1) + in.minutes[prev][node];
        double s = Math.max(arrive, in.earliest[node]);
        if (s > in.latest[node] + 1e-9) {
            return Double.NaN;
        }
        double arriveNext = s + in.service[node] + in.minutes[node][next];
        if (pos < seq.length) {
            if (Math.max(arriveNext, in.earliest[next]) > latestStart[pos] + 1e-9) {
                return Double.NaN;
            }
        } else if (arriveNext > endLatest + 1e-9) {
            return Double.NaN;
        }
        return in.km[prev][node] + in.km[node][next] - in.km[prev][next];
    }

    void insert(int node, int pos) {
        int[] s = new int[seq.length + 1];
        System.arraycopy(seq, 0, s, 0, pos);
        s[pos] = node;
        System.arraycopy(seq, pos, s, pos + 1, seq.length - pos);
        set(s);
    }

    int removeAt(int pos) {
        int node = seq[pos];
        int[] s = new int[seq.length - 1];
        System.arraycopy(seq, 0, s, 0, pos);
        System.arraycopy(seq, pos + 1, s, pos, seq.length - pos - 1);
        set(s);
        return node;
    }

    /** km saved by taking out the stop at {@code pos}. */
    double removalGain(int pos) {
        int prev = nodeAt(pos - 1);
        int node = seq[pos];
        int next = nodeAt(pos + 1);
        return in.km[prev][node] + in.km[node][next] - in.km[prev][next];
    }

    Plan.RoutePlan toPlan() {
        var stops = new java.util.ArrayList<Plan.PlannedStop>(seq.length);
        double t = in.vehicle(vehicle).available();
        int prev = in.startNode(vehicle);
        for (int i = 0; i < seq.length; i++) {
            int node = seq[i];
            double arrive = t + in.minutes[prev][node];
            stops.add(new Plan.PlannedStop(in.problem.jobs().get(node - 1 - in.vehicles).id(), arrive, start[i],
                    start[i] + in.service[node]));
            t = start[i] + in.service[node];
            prev = node;
        }
        return new Plan.RoutePlan(in.vehicle(vehicle).id(), stops, km, returnAt);
    }

    @Override
    public String toString() {
        return "route(v" + vehicle + " " + Arrays.toString(seq) + " km=" + km + ")";
    }
}
