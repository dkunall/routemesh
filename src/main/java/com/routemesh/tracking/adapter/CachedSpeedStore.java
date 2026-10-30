package com.routemesh.tracking.adapter;

import com.routemesh.tracking.application.TrackingStores.SpeedStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * segment_speed in Postgres, with an in-memory copy for reads: an ETA walks dozens of cells, and the table is
 * small (cells x 24 hours). Samples are folded in with an EWMA inside the upsert, so concurrent consumers do not
 * lose each other's updates. A cell needs {@link #MIN_SAMPLES} before it is trusted.
 */
@Repository
class CachedSpeedStore implements SpeedStore {

    static final long MIN_SAMPLES = 3;

    private final JdbcClient db;
    private volatile Map<Key, Double> speeds = Map.of();

    CachedSpeedStore(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void record(long cell, int hour, double kmh, double alpha, Instant now) {
        db.sql("""
                        insert into segment_speed (cell, hour, kmh, samples, updated_at) values (?, ?, ?, 1, ?)
                        on conflict (cell, hour) do update set
                            kmh = segment_speed.kmh + ? * (excluded.kmh - segment_speed.kmh),
                            samples = segment_speed.samples + 1, updated_at = excluded.updated_at""")
                .params(cell, hour, kmh, Timestamp.from(now), alpha).update();
    }

    @Override
    @Scheduled(fixedDelayString = "PT15S")
    public void refresh() {
        var next = new ConcurrentHashMap<Key, Double>();
        db.sql("select cell, hour, kmh from segment_speed where samples >= ?").param(MIN_SAMPLES)
                .query(rs -> {
                    next.put(new Key(rs.getLong(1), rs.getInt(2)), rs.getDouble(3));
                });
        speeds = next;
    }

    @Override
    public double kmh(long cell, int hourOfDay) {
        return speeds.getOrDefault(new Key(cell, hourOfDay), Double.NaN);
    }

    @Override
    public double areaKmh(int hourOfDay) {
        return kmh(AREA, hourOfDay);
    }

    private record Key(long cell, int hour) {
    }
}
