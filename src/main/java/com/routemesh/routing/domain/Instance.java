package com.routemesh.routing.domain;

import com.routemesh.geo.domain.GeoPoint;

/**
 * The problem as arrays, with a dense distance matrix. Node 0 is the depot, nodes 1..V are vehicle starts and
 * nodes V+1..V+N are jobs. Built once per solve; at a few hundred stops the matrix is a few hundred KB.
 */
final class Instance {

    final Problem problem;
    final int vehicles;
    final int jobs;
    final double[][] km;
    final double[][] minutes;
    final double[] earliest;
    final double[] latest;
    final double[] service;
    final int[] demand;
    final Job.Kind[] kind;

    Instance(Problem p) {
        this.problem = p;
        this.vehicles = p.vehicles().size();
        this.jobs = p.jobs().size();
        int n = 1 + vehicles + jobs;
        GeoPoint[] at = new GeoPoint[n];
        at[0] = p.depot();
        for (int v = 0; v < vehicles; v++) {
            GeoPoint s = p.vehicles().get(v).start();
            at[1 + v] = s == null ? p.depot() : s;
        }
        earliest = new double[n];
        latest = new double[n];
        service = new double[n];
        demand = new int[n];
        kind = new Job.Kind[n];
        for (int j = 0; j < jobs; j++) {
            Job job = p.jobs().get(j);
            int node = jobNode(j);
            at[node] = job.at();
            earliest[node] = job.earliest();
            latest[node] = job.latest();
            service[node] = job.serviceMinutes();
            demand[node] = job.demandKg();
            kind[node] = job.kind();
        }
        km = new double[n][n];
        minutes = new double[n][n];
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                double d = p.travel().km(at[a], at[b]);
                km[a][b] = km[b][a] = d;
                minutes[a][b] = minutes[b][a] = p.travel().minutes(d);
            }
        }
    }

    int jobNode(int job) {
        return 1 + vehicles + job;
    }

    int startNode(int vehicle) {
        return 1 + vehicle;
    }

    Vehicle vehicle(int v) {
        return problem.vehicles().get(v);
    }
}
