package com.routemesh.fleet.adapter;

import com.routemesh.fleet.application.FleetStore;
import com.routemesh.fleet.domain.Driver;
import com.routemesh.fleet.domain.FleetVehicle;
import com.routemesh.fleet.domain.Hub;
import com.routemesh.geo.adapter.Pg;
import com.routemesh.shared.security.Device;
import com.routemesh.shared.security.DeviceTokens;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcFleetStore implements FleetStore, DeviceTokens {

    private final JdbcClient db;

    JdbcFleetStore(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void insertHub(Hub hub, double fenceMeters, Instant now) {
        var p = hub.location();
        db.sql("insert into hub (id, code, name, location, created_at) values (?, ?, ?, " + Pg.POINT + ", ?)")
                .params(hub.id(), hub.code(), hub.name(), p.lng(), p.lat(), Timestamp.from(now)).update();
        db.sql("insert into geofence (id, kind, ref_id, center, radius_m) values (?, 'HUB', ?, " + Pg.POINT + ", ?)")
                .params(UUID.randomUUID(), hub.id(), p.lng(), p.lat(), fenceMeters).update();
    }

    @Override
    public Optional<Hub> hub(UUID id) {
        return db.sql("select id, code, name, " + Pg.cols("location", "loc") + " from hub where id = ?")
                .param(id)
                .query((rs, n) -> new Hub(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        Pg.read(rs, "loc")))
                .optional();
    }

    @Override
    public void insertVehicle(FleetVehicle v) {
        db.sql("insert into vehicle (id, hub_id, code, capacity_kg, shift_start, shift_end) values (?, ?, ?, ?, ?, ?)")
                .params(v.id(), v.hubId(), v.code(), v.capacityKg(), Time.valueOf(v.shiftStart()), Time.valueOf(v.shiftEnd()))
                .update();
    }

    private static final String VEHICLE = "select id, hub_id, code, capacity_kg, shift_start, shift_end from vehicle ";

    @Override
    public List<FleetVehicle> activeVehicles(UUID hubId) {
        return db.sql(VEHICLE + "where hub_id = ? and active order by code").param(hubId).query(this::mapVehicle).list();
    }

    @Override
    public Optional<FleetVehicle> vehicle(UUID id) {
        return db.sql(VEHICLE + "where id = ?").param(id).query(this::mapVehicle).optional();
    }

    private FleetVehicle mapVehicle(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new FleetVehicle(rs.getObject("id", UUID.class), rs.getObject("hub_id", UUID.class),
                rs.getString("code"), rs.getInt("capacity_kg"), rs.getTime("shift_start").toLocalTime(),
                rs.getTime("shift_end").toLocalTime());
    }

    @Override
    public void insertDriver(Driver d, Instant now) {
        db.sql("insert into driver (id, hub_id, code, name, created_at) values (?, ?, ?, ?, ?)")
                .params(d.id(), d.hubId(), d.code(), d.name(), Timestamp.from(now)).update();
    }

    @Override
    public Optional<Driver> driver(UUID id) {
        return db.sql("select id, hub_id, code, name from driver where id = ?").param(id)
                .query((rs, n) -> new Driver(rs.getObject("id", UUID.class), rs.getObject("hub_id", UUID.class),
                        rs.getString("code"), rs.getString("name")))
                .optional();
    }

    @Override
    public void insertDevice(UUID id, UUID driverId, String tokenSha256, Instant now) {
        db.sql("insert into device (id, driver_id, token_sha256, created_at) values (?, ?, ?, ?)")
                .params(id, driverId, tokenSha256, Timestamp.from(now)).update();
    }

    @Override
    public Optional<Device> findActive(String tokenSha256Hex) {
        return db.sql("select id, driver_id from device where token_sha256 = ? and active").param(tokenSha256Hex)
                .query((rs, n) -> new Device(rs.getObject("id", UUID.class), rs.getObject("driver_id", UUID.class)))
                .optional();
    }
}
