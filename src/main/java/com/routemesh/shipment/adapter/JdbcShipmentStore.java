package com.routemesh.shipment.adapter;

import com.routemesh.geo.adapter.Pg;
import com.routemesh.shipment.application.ShipmentStore;
import com.routemesh.shipment.domain.Shipment;
import com.routemesh.shipment.domain.ShipmentKind;
import com.routemesh.shipment.domain.ShipmentStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcShipmentStore implements ShipmentStore {

    private static final String COLS = "id, hub_id, shipper_subject, reference, kind, " + Pg.cols("drop_point", "drop")
            + ", address, recipient_name, recipient_email, recipient_phone, service_date, window_start, window_end, "
            + "weight_kg, status, attempts, route_id, tracking_token, version, created_at ";

    private final JdbcClient db;

    JdbcShipmentStore(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void insert(Shipment s, double fenceMeters) {
        db.sql("""
                        insert into shipment (id, hub_id, shipper_subject, reference, kind, drop_point, address,
                            recipient_name, recipient_email, recipient_phone, service_date, window_start, window_end,
                            weight_kg, status, attempts, tracking_token, version, created_at, updated_at)
                        values (?, ?, ?, ?, ?, %s, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 0, ?, ?)""".formatted(Pg.POINT))
                .params(s.id(), s.hubId(), s.shipperSubject(), s.reference(), s.kind().name(), s.drop().lng(),
                        s.drop().lat(), s.address(), s.recipientName(), s.recipientEmail(), s.recipientPhone(),
                        Date.valueOf(s.serviceDate()), ts(s.windowStart()), ts(s.windowEnd()), s.weightKg(),
                        s.status().name(), s.trackingToken(), ts(s.createdAt()), ts(s.createdAt()))
                .update();
        db.sql("insert into geofence (id, kind, ref_id, center, radius_m) values (?, 'DROP', ?, " + Pg.POINT + ", ?)")
                .params(UUID.randomUUID(), s.id(), s.drop().lng(), s.drop().lat(), fenceMeters).update();
    }

    @Override
    public Optional<Shipment> find(UUID id) {
        return db.sql("select " + COLS + "from shipment where id = ?").param(id).query(this::map).optional();
    }

    @Override
    public Optional<Shipment> lock(UUID id) {
        return db.sql("select " + COLS + "from shipment where id = ? for update").param(id).query(this::map).optional();
    }

    @Override
    public List<Shipment> lockAll(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return db.sql("select " + COLS + "from shipment where id = any(?::uuid[]) order by id for update")
                .param(ids.stream().map(UUID::toString).toArray(String[]::new)).query(this::map).list();
    }

    @Override
    public Optional<Shipment> byToken(String token) {
        return db.sql("select " + COLS + "from shipment where tracking_token = ?").param(token).query(this::map)
                .optional();
    }

    @Override
    public boolean update(Shipment s, Instant now) {
        return db.sql("""
                        update shipment set status = ?, attempts = ?, route_id = ?, service_date = ?, window_start = ?,
                            window_end = ?, version = version + 1, updated_at = ?
                        where id = ? and version = ?""")
                .params(s.status().name(), s.attempts(), s.routeId(), Date.valueOf(s.serviceDate()),
                        ts(s.windowStart()), ts(s.windowEnd()), ts(now), s.id(), s.version())
                .update() == 1;
    }

    @Override
    public List<Shipment> plannable(UUID hubId, LocalDate date) {
        return db.sql("select " + COLS + """
                        from shipment where hub_id = ? and service_date = ? and status in ('CREATED', 'FAILED_ATTEMPT')
                        order by created_at, id""")
                .params(hubId, Date.valueOf(date)).query(this::map).list();
    }

    @Override
    public List<Shipment> onRoute(UUID routeId) {
        return db.sql("select " + COLS + "from shipment where route_id = ? order by id").param(routeId)
                .query(this::map).list();
    }

    @Override
    public void setOtp(UUID id, String hash) {
        db.sql("update shipment set otp_hash = ?, otp_failures = 0 where id = ?").params(hash, id).update();
    }

    @Override
    public Optional<String> otpHash(UUID id) {
        return db.sql("select otp_hash from shipment where id = ?").param(id).query(String.class).optional();
    }

    @Override
    public int otpFailed(UUID id) {
        return db.sql("update shipment set otp_failures = otp_failures + 1 where id = ? returning otp_failures")
                .param(id).query(Integer.class).single();
    }

    @Override
    public void deactivateFence(UUID shipmentId) {
        db.sql("update geofence set active = false where kind = 'DROP' and ref_id = ?").param(shipmentId).update();
    }

    private Shipment map(ResultSet rs, int n) throws SQLException {
        return new Shipment(rs.getObject("id", UUID.class), rs.getObject("hub_id", UUID.class),
                rs.getString("shipper_subject"), rs.getString("reference"), ShipmentKind.valueOf(rs.getString("kind")),
                Pg.read(rs, "drop"), rs.getString("address"), rs.getString("recipient_name"),
                rs.getString("recipient_email"), rs.getString("recipient_phone"),
                rs.getDate("service_date").toLocalDate(), rs.getTimestamp("window_start").toInstant(),
                rs.getTimestamp("window_end").toInstant(), rs.getInt("weight_kg"),
                ShipmentStatus.valueOf(rs.getString("status")), rs.getInt("attempts"),
                rs.getObject("route_id", UUID.class), rs.getString("tracking_token"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
