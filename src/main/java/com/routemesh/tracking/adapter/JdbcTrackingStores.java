package com.routemesh.tracking.adapter;

import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.tracking.application.TrackingStores.AlertStore;
import com.routemesh.tracking.application.TrackingStores.Fence;
import com.routemesh.tracking.application.TrackingStores.GeofenceStore;
import com.routemesh.tracking.application.TrackingStores.PingStore;
import com.routemesh.tracking.application.TrackingStores.View;
import com.routemesh.tracking.application.TrackingStores.ViewStore;
import com.routemesh.geo.domain.Ping;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
class JdbcTrackingStores implements PingStore, GeofenceStore, AlertStore, ViewStore {

    private final JdbcClient db;

    JdbcTrackingStores(JdbcClient db) {
        this.db = db;
    }

    // --- pings

    @Override
    public int save(UUID driverId, List<Ping> pings) {
        int n = 0;
        for (var p : pings) {
            n += db.sql("""
                            insert into location_ping (driver_id, at, position)
                            values (?, ?, st_setsrid(st_makepoint(?, ?), 4326)::geography) on conflict do nothing""")
                    .params(driverId, Timestamp.from(p.at()), p.position().lng(), p.position().lat()).update();
        }
        return n;
    }

    @Override
    public int purgeBefore(Instant cutoff) {
        return db.sql("delete from location_ping where at < ?").param(Timestamp.from(cutoff)).update();
    }

    // --- geofences

    @Override
    public List<Fence> containing(GeoPoint p) {
        return db.sql("""
                        select id, kind, ref_id from geofence
                        where active and st_dwithin(center, st_setsrid(st_makepoint(?, ?), 4326)::geography, radius_m)""")
                .params(p.lng(), p.lat())
                .query((rs, n) -> new Fence(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class)))
                .list();
    }

    @Override
    public Set<UUID> presence(UUID driverId) {
        return new HashSet<>(db.sql("select fence_id from geofence_presence where driver_id = ?").param(driverId)
                .query(UUID.class).list());
    }

    @Override
    public Optional<Fence> fence(UUID id) {
        return db.sql("select id, kind, ref_id from geofence where id = ?").param(id)
                .query((rs, n) -> new Fence(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class)))
                .optional();
    }

    @Override
    public void enter(UUID driverId, UUID fenceId, Instant at) {
        db.sql("insert into geofence_presence (driver_id, fence_id, entered_at) values (?, ?, ?) on conflict do nothing")
                .params(driverId, fenceId, Timestamp.from(at)).update();
    }

    @Override
    public void exit(UUID driverId, UUID fenceId) {
        db.sql("delete from geofence_presence where driver_id = ? and fence_id = ?").params(driverId, fenceId).update();
    }

    // --- alerts

    @Override
    public boolean raise(String kind, UUID shipmentId, UUID routeId, String message, Instant now) {
        return db.sql("""
                        insert into alert (kind, shipment_id, route_id, message, created_at) values (?, ?, ?, ?, ?)
                        on conflict (kind, shipment_id) do nothing""")
                .params(kind, shipmentId, routeId, message, Timestamp.from(now)).update() == 1;
    }

    // --- tracking view

    @Override
    public void upsert(View v) {
        db.sql("""
                        insert into tracking_view (shipment_id, tracking_token, reference, status, window_start, window_end,
                            eta, attempts, driver_name, driver_id, last_event_at, delivered_at, expires_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                        on conflict (shipment_id) do update set status = excluded.status,
                            window_start = excluded.window_start, window_end = excluded.window_end,
                            eta = case when excluded.status = tracking_view.status then tracking_view.eta end,
                            attempts = excluded.attempts, driver_name = excluded.driver_name,
                            driver_id = excluded.driver_id, last_event_at = excluded.last_event_at,
                            delivered_at = excluded.delivered_at, expires_at = excluded.expires_at, updated_at = now()
                        where tracking_view.last_event_at <= excluded.last_event_at""")
                .params(v.shipmentId(), v.trackingToken(), v.reference(), v.status(), ts(v.windowStart()),
                        ts(v.windowEnd()), ts(v.eta()), v.attempts(), v.driverName(), v.driverId(),
                        ts(v.lastEventAt()), ts(v.deliveredAt()), ts(v.expiresAt()))
                .update();
    }

    @Override
    public void eta(UUID shipmentId, Instant eta) {
        db.sql("update tracking_view set eta = ?, updated_at = now() where shipment_id = ?")
                .params(ts(eta), shipmentId).update();
    }

    @Override
    public Optional<View> byToken(String token) {
        return db.sql("""
                        select shipment_id, tracking_token, reference, status, window_start, window_end, eta, attempts,
                            driver_name, driver_id, last_event_at, delivered_at, expires_at from tracking_view
                        where tracking_token = ?""")
                .param(token).query(this::view).optional();
    }

    @Override
    public int purgeExpired(Instant now) {
        return db.sql("delete from tracking_view where expires_at < ?").param(Timestamp.from(now)).update();
    }

    private View view(ResultSet rs, int n) throws SQLException {
        return new View(rs.getObject("shipment_id", UUID.class), rs.getString("tracking_token"),
                rs.getString("reference"), rs.getString("status"), inst(rs, "window_start"), inst(rs, "window_end"),
                inst(rs, "eta"), rs.getInt("attempts"), rs.getString("driver_name"),
                rs.getObject("driver_id", UUID.class), inst(rs, "last_event_at"), inst(rs, "delivered_at"),
                inst(rs, "expires_at"));
    }

    private static Instant inst(ResultSet rs, String c) throws SQLException {
        var t = rs.getTimestamp(c);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
