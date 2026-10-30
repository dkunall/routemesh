package com.routemesh.fleet.adapter;

import com.routemesh.fleet.application.FleetService;
import com.routemesh.fleet.domain.Driver;
import com.routemesh.fleet.domain.FleetVehicle;
import com.routemesh.fleet.domain.Hub;
import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.shared.error.ApiException;
import com.routemesh.shared.security.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;
import java.util.UUID;

@RestController
class FleetController {

    record HubIn(String code, String name, Double lat, Double lng) {
    }

    record VehicleIn(String code, Integer capacityKg, LocalTime shiftStart, LocalTime shiftEnd) {
    }

    record DriverIn(String code, String name) {
    }

    private final FleetService fleet;

    FleetController(FleetService fleet) {
        this.fleet = fleet;
    }

    @PostMapping("/v1/hubs")
    @ResponseStatus(HttpStatus.CREATED)
    Hub hub(Caller caller, @RequestBody HubIn in) {
        if (in.code() == null || in.name() == null || in.lat() == null || in.lng() == null) {
            throw ApiException.invalid("code, name, lat and lng are required");
        }
        GeoPoint p;
        try {
            p = new GeoPoint(in.lat(), in.lng());
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid(e.getMessage());
        }
        return fleet.createHub(caller, in.code(), in.name(), p);
    }

    @PostMapping("/v1/hubs/{hubId}/vehicles")
    @ResponseStatus(HttpStatus.CREATED)
    FleetVehicle vehicle(Caller caller, @PathVariable UUID hubId, @RequestBody VehicleIn in) {
        if (in.code() == null || in.capacityKg() == null || in.shiftStart() == null || in.shiftEnd() == null) {
            throw ApiException.invalid("code, capacityKg, shiftStart and shiftEnd are required");
        }
        return fleet.addVehicle(caller, hubId, in.code(), in.capacityKg(), in.shiftStart(), in.shiftEnd());
    }

    @PostMapping("/v1/hubs/{hubId}/drivers")
    @ResponseStatus(HttpStatus.CREATED)
    Driver driver(Caller caller, @PathVariable UUID hubId, @RequestBody DriverIn in) {
        if (in.code() == null || in.name() == null) {
            throw ApiException.invalid("code and name are required");
        }
        return fleet.addDriver(caller, hubId, in.code(), in.name());
    }

    /** The token is in this response only; the server keeps its SHA-256. */
    @PostMapping("/v1/drivers/{driverId}/devices")
    @ResponseStatus(HttpStatus.CREATED)
    FleetService.IssuedDevice device(Caller caller, @PathVariable UUID driverId) {
        return fleet.issueDevice(caller, driverId);
    }
}
