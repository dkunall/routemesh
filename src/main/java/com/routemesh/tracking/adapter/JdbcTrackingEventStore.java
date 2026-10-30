package com.routemesh.tracking.adapter;

import com.routemesh.tracking.application.TrackingEventStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcTrackingEventStore implements TrackingEventStore {

    private static final String COLS = "select id, device_seq, shipment_id, status, reason, st_y(position::geometry) lat, "
            + "st_x(position::geometry) lng, state, outcome, received_at from tracking_event ";

    private final JdbcClient db;

    JdbcTrackingEventStore(JdbcClient db) {
        this.db = db;
    }

    @Override
    public long lockDevice(UUID deviceId) {
        return db.sql("select applied_seq from device where id = ? for update").param(deviceId).query(Long.class).single();
    }

    @Override
    public void setCursor(UUID deviceId, long seq) {
        db.sql("update device set applied_seq = greatest(applied_seq, ?) where id = ?").params(seq, deviceId).update();
    }

    @Override
    public boolean insert(UUID deviceId, EventIn e, String state, String outcome, Instant now) {
        boolean hasPos = e.lat() != null && e.lng() != null;
        return db.sql("""
                        insert into tracking_event (device_id, device_seq, shipment_id, status, reason, position, device_at,
                            received_at, state, outcome, applied_at)
                        values (?, ?, ?, ?, ?, case when ? then st_setsrid(st_makepoint(?, ?), 4326)::geography end,
                            ?, ?, ?, ?, ?)
                        on conflict (device_id, device_seq) do nothing""")
                .params(deviceId, e.deviceSeq(), e.shipmentId(), e.status(), e.reason(), hasPos,
                        hasPos ? e.lng() : 0.0, hasPos ? e.lat() : 0.0,
                        e.deviceAt() == null ? null : Timestamp.from(e.deviceAt()), Timestamp.from(now), state, outcome,
                        state.equals("PENDING") ? null : Timestamp.from(now))
                .update() == 1;
    }

    @Override
    public Optional<Stored> find(UUID deviceId, long seq) {
        return db.sql(COLS + "where device_id = ? and device_seq = ?").params(deviceId, seq).query(this::map).optional();
    }

    @Override
    public List<Stored> after(UUID deviceId, long seq) {
        return db.sql(COLS + "where device_id = ? and device_seq > ? order by device_seq").params(deviceId, seq)
                .query(this::map).list();
    }

    @Override
    public void outcome(long id, String state, String outcome, Instant now) {
        db.sql("update tracking_event set state = ?, outcome = ?, applied_at = ? where id = ?")
                .params(state, outcome, Timestamp.from(now), id).update();
    }

    @Override
    public List<UUID> waitingSince(Instant before) {
        return db.sql("""
                        select distinct e.device_id from tracking_event e join device d on d.id = e.device_id
                        where e.state = 'PENDING' and e.received_at < ? and e.device_seq > d.applied_seq + 1""")
                .param(Timestamp.from(before)).query(UUID.class).list();
    }

    @Override
    public List<Stored> forShipment(UUID shipmentId) {
        return db.sql(COLS + "where shipment_id = ? order by received_at, id").param(shipmentId).query(this::map).list();
    }

    private Stored map(ResultSet rs, int n) throws SQLException {
        Double lat = rs.getObject("lat") == null ? null : rs.getDouble("lat");
        Double lng = rs.getObject("lng") == null ? null : rs.getDouble("lng");
        return new Stored(rs.getLong("id"), rs.getLong("device_seq"), rs.getObject("shipment_id", UUID.class),
                rs.getString("status"), rs.getString("reason"), lat, lng, rs.getString("state"),
                rs.getString("outcome"), rs.getTimestamp("received_at").toInstant());
    }
}
