package com.routemesh.pod.adapter;

import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.pod.application.PodStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcPodStore implements PodStore {

    private final JdbcClient db;

    JdbcPodStore(JdbcClient db) {
        this.db = db;
    }

    @Override
    public double distanceMeters(UUID shipmentId, GeoPoint p) {
        return db.sql("select st_distance(drop_point, st_setsrid(st_makepoint(?, ?), 4326)::geography) from shipment where id = ?")
                .params(p.lng(), p.lat(), shipmentId).query(Double.class).single();
    }

    @Override
    public void insert(Pod pod, byte[] photo, GeoPoint at) {
        db.sql("""
                        insert into pod (shipment_id, device_id, otp_verified, photo, photo_type, photo_sha256, position,
                            distance_m, delivered_at)
                        values (?, ?, true, ?, ?, ?, st_setsrid(st_makepoint(?, ?), 4326)::geography, ?, ?)""")
                .params(pod.shipmentId(), pod.deviceId(), photo, pod.photoType(), pod.photoSha256(), at.lng(), at.lat(),
                        pod.distanceMeters(), Timestamp.from(pod.deliveredAt()))
                .update();
    }

    @Override
    public Optional<Pod> find(UUID shipmentId) {
        return db.sql("select shipment_id, device_id, photo_type, photo_sha256, distance_m, delivered_at from pod where shipment_id = ?")
                .param(shipmentId)
                .query((rs, n) -> new Pod(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getDouble(5), rs.getTimestamp(6).toInstant()))
                .optional();
    }
}
