package com.routemesh.fleet.application;

import com.routemesh.fleet.domain.Driver;
import com.routemesh.fleet.domain.FleetVehicle;
import com.routemesh.fleet.domain.Hub;
import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.shared.config.RouteMeshProperties;
import com.routemesh.shared.error.ApiException;
import com.routemesh.shared.security.Caller;
import com.routemesh.shared.security.DeviceAuth;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalTime;
import java.util.Base64;
import java.util.UUID;

/** Hubs, vehicles, drivers and driver device tokens. Dispatcher-only. */
@Service
public class FleetService {

    public record IssuedDevice(UUID deviceId, UUID driverId, String token) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final FleetStore store;
    private final RouteMeshProperties props;
    private final Clock clock;

    public FleetService(FleetStore store, RouteMeshProperties props, Clock clock) {
        this.store = store;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public Hub createHub(Caller caller, String code, String name, GeoPoint location) {
        caller.requireDispatcher();
        var hub = new Hub(UUID.randomUUID(), code, name, location);
        try {
            store.insertHub(hub, props.delivery().hubFenceMeters(), clock.instant());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("duplicate-code", "a hub with code " + code + " exists");
        }
        return hub;
    }

    @Transactional
    public FleetVehicle addVehicle(Caller caller, UUID hubId, String code, int capacityKg, LocalTime shiftStart,
                                   LocalTime shiftEnd) {
        caller.requireDispatcher();
        hub(hubId);
        FleetVehicle v;
        try {
            v = new FleetVehicle(UUID.randomUUID(), hubId, code, capacityKg, shiftStart, shiftEnd);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid(e.getMessage());
        }
        try {
            store.insertVehicle(v);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("duplicate-code", "a vehicle with code " + code + " exists");
        }
        return v;
    }

    @Transactional
    public Driver addDriver(Caller caller, UUID hubId, String code, String name) {
        caller.requireDispatcher();
        hub(hubId);
        var d = new Driver(UUID.randomUUID(), hubId, code, name);
        try {
            store.insertDriver(d, clock.instant());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("duplicate-code", "a driver with code " + code + " exists");
        }
        return d;
    }

    /** The raw token is returned once and never stored; only its SHA-256 is. */
    @Transactional
    public IssuedDevice issueDevice(Caller caller, UUID driverId) {
        caller.requireDispatcher();
        store.driver(driverId).orElseThrow(() -> ApiException.notFound("driver"));
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = "rmd_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        var id = UUID.randomUUID();
        store.insertDevice(id, driverId, DeviceAuth.sha256(token), clock.instant());
        return new IssuedDevice(id, driverId, token);
    }

    public Hub hub(UUID id) {
        return store.hub(id).orElseThrow(() -> ApiException.notFound("hub"));
    }
}
