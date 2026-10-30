package com.routemesh.routing.adapter;

import com.routemesh.routing.application.RouteRow;
import com.routemesh.routing.application.RouteStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcRouteStore implements RouteStore {

    private static final String COLS = "select id, hub_id, service_date, vehicle_id, driver_id, status, km, "
            + "planned_return, version from route ";

    private final JdbcClient db;

    JdbcRouteStore(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void lockHub(UUID hubId) {
        db.sql("select id from hub where id = ? for update").param(hubId).query(UUID.class).single();
    }

    @Override
    public List<UUID> vehiclesWithRoute(UUID hubId, LocalDate date) {
        return db.sql("select vehicle_id from route where hub_id = ? and service_date = ?")
                .params(hubId, Date.valueOf(date)).query(UUID.class).list();
    }

    @Override
    public void insert(RouteRow r, Instant now) {
        db.sql("""
                        insert into route (id, hub_id, service_date, vehicle_id, driver_id, status, km, planned_return,
                            version, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)""")
                .params(r.id(), r.hubId(), Date.valueOf(r.serviceDate()), r.vehicleId(), r.driverId(),
                        r.status().name(), r.km(), ts(r.plannedReturn()), ts(now), ts(now))
                .update();
    }

    @Override
    public Optional<RouteRow> find(UUID id) {
        return db.sql(COLS + "where id = ?").param(id).query(this::map).optional();
    }

    @Override
    public List<RouteRow> forDate(UUID hubId, LocalDate date, RouteRow.Status status) {
        return db.sql(COLS + "where hub_id = ? and service_date = ? and status = ? order by id")
                .params(hubId, Date.valueOf(date), status.name()).query(this::map).list();
    }

    @Override
    public Optional<RouteRow> activeForDriver(UUID driverId) {
        return db.sql(COLS + "where driver_id = ? and status = 'DISPATCHED'").param(driverId).query(this::map)
                .optional();
    }

    @Override
    public boolean bump(UUID id, long expectedVersion, double km, Instant plannedReturn, Instant now) {
        return db.sql("""
                        update route set km = ?, planned_return = ?, version = version + 1, updated_at = ?
                        where id = ? and version = ? and status <> 'COMPLETED'""")
                .params(km, ts(plannedReturn), ts(now), id, expectedVersion).update() == 1;
    }

    @Override
    public boolean dispatch(UUID id, long expectedVersion, UUID driverId, Instant now) {
        return db.sql("""
                        update route set status = 'DISPATCHED', driver_id = ?, version = version + 1, updated_at = ?
                        where id = ? and version = ? and status = 'PLANNED'""")
                .params(driverId, ts(now), id, expectedVersion).update() == 1;
    }

    @Override
    public void complete(UUID id, Instant now) {
        db.sql("update route set status = 'COMPLETED', version = version + 1, updated_at = ? where id = ? and status = 'DISPATCHED'")
                .params(ts(now), id).update();
    }

    @Override
    public List<Stop> stops(UUID routeId) {
        return db.sql("""
                        select seq, shipment_id, planned_eta, predicted_eta, arrived_at from route_stop
                        where route_id = ? order by seq""")
                .param(routeId)
                .query((rs, n) -> new Stop(rs.getInt("seq"), rs.getObject("shipment_id", UUID.class),
                        inst(rs, "planned_eta"), inst(rs, "predicted_eta"), inst(rs, "arrived_at")))
                .list();
    }

    @Override
    public void replaceStops(UUID routeId, List<UUID> shipments, List<Instant> plannedEtas) {
        var kept = stops(routeId);
        db.sql("delete from route_stop where route_id = ?").param(routeId).update();
        for (int i = 0; i < shipments.size(); i++) {
            var id = shipments.get(i);
            db.sql("delete from route_stop where shipment_id = ?").param(id).update();
            var old = kept.stream().filter(s -> s.shipmentId().equals(id)).findFirst();
            db.sql("""
                            insert into route_stop (route_id, seq, shipment_id, planned_eta, predicted_eta, arrived_at)
                            values (?, ?, ?, ?, ?, ?)""")
                    .params(routeId, i + 1, id, ts(plannedEtas.get(i)),
                            old.map(s -> ts(s.predictedEta())).orElse(null), old.map(s -> ts(s.arrivedAt())).orElse(null))
                    .update();
        }
    }

    @Override
    public void predicted(UUID routeId, UUID shipmentId, Instant eta) {
        db.sql("update route_stop set predicted_eta = ? where route_id = ? and shipment_id = ?")
                .params(ts(eta), routeId, shipmentId).update();
    }

    @Override
    public boolean arrived(UUID shipmentId, Instant at) {
        return db.sql("update route_stop set arrived_at = ? where shipment_id = ? and arrived_at is null")
                .params(ts(at), shipmentId).update() == 1;
    }

    private RouteRow map(ResultSet rs, int n) throws SQLException {
        return new RouteRow(rs.getObject("id", UUID.class), rs.getObject("hub_id", UUID.class),
                rs.getDate("service_date").toLocalDate(), rs.getObject("vehicle_id", UUID.class),
                rs.getObject("driver_id", UUID.class), RouteRow.Status.valueOf(rs.getString("status")),
                rs.getDouble("km"), inst(rs, "planned_return"), rs.getLong("version"));
    }

    private static Instant inst(ResultSet rs, String col) throws SQLException {
        var t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
