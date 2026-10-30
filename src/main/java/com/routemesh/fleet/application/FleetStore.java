package com.routemesh.fleet.application;

import com.routemesh.fleet.domain.Driver;
import com.routemesh.fleet.domain.FleetVehicle;
import com.routemesh.fleet.domain.Hub;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FleetStore {

    void insertHub(Hub hub, double fenceMeters, Instant now);

    Optional<Hub> hub(UUID id);

    void insertVehicle(FleetVehicle v);

    List<FleetVehicle> activeVehicles(UUID hubId);

    Optional<FleetVehicle> vehicle(UUID id);

    void insertDriver(Driver d, Instant now);

    Optional<Driver> driver(UUID id);

    void insertDevice(UUID id, UUID driverId, String tokenSha256, Instant now);
}
