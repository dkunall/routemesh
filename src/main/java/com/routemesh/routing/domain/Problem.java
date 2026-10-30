package com.routemesh.routing.domain;

import com.routemesh.geo.domain.GeoPoint;

import java.util.List;

/** Vehicles, jobs and a depot (the hub) all routes return to. */
public record Problem(GeoPoint depot, List<Vehicle> vehicles, List<Job> jobs, TravelModel travel) {

    public Problem {
        vehicles = List.copyOf(vehicles);
        jobs = List.copyOf(jobs);
        if (vehicles.isEmpty()) {
            throw new IllegalArgumentException("no vehicles");
        }
    }
}
