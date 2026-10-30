package com.routemesh.geo.adapter;

import com.routemesh.geo.application.DriverIndex;
import com.routemesh.geo.domain.CellIndex;
import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.shared.config.RouteMeshProperties;
import com.routemesh.geo.domain.Ping;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Last fix per driver in {@code rm:drv:{id}} and a set of drivers per H3 cell in {@code rm:cell:{res}:{cell}}.
 * A "near" query reads k rings of cell sets instead of every driver. Both keys expire after a day, so a phone that
 * goes silent drops out on its own; freshness is still checked against the fix time.
 */
@Component
class RedisDriverIndex implements DriverIndex {

    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final CellIndex cells;
    private final int res;

    RedisDriverIndex(StringRedisTemplate redis, CellIndex cells, RouteMeshProperties props) {
        this.redis = redis;
        this.cells = cells;
        this.res = props.tracking().h3Resolution();
    }

    @Override
    public void update(UUID driverId, Ping last) {
        String key = "rm:drv:" + driverId;
        var old = redis.<String, String>opsForHash().entries(key);
        if (old.containsKey("at") && !Instant.parse(old.get("at").toString()).isBefore(last.at())) {
            return;
        }
        long cell = cells.cell(last.position(), res);
        String oldCell = (String) old.get("cell");
        if (oldCell != null && !oldCell.equals(Long.toString(cell))) {
            redis.opsForSet().remove(cellKey(Long.parseLong(oldCell)), driverId.toString());
        }
        redis.opsForHash().putAll(key, Map.of("lat", Double.toString(last.position().lat()),
                "lng", Double.toString(last.position().lng()), "at", last.at().toString(), "cell", Long.toString(cell)));
        redis.expire(key, TTL);
        redis.opsForSet().add(cellKey(cell), driverId.toString());
        redis.expire(cellKey(cell), TTL);
    }

    @Override
    public Optional<Ping> last(UUID driverId) {
        var h = redis.<String, String>opsForHash().entries("rm:drv:" + driverId);
        if (!h.containsKey("at")) {
            return Optional.empty();
        }
        return Optional.of(new Ping(driverId.toString(), Instant.parse(h.get("at")),
                new GeoPoint(Double.parseDouble(h.get("lat")), Double.parseDouble(h.get("lng")))));
    }

    @Override
    public List<Nearby> near(GeoPoint p, int k, Instant freshAfter) {
        long origin = cells.cell(p, res);
        var out = new ArrayList<Nearby>();
        for (int ring = 0; ring <= k; ring++) {
            for (long c : cells.ring(origin, ring)) {
                var members = redis.opsForSet().members(cellKey(c));
                if (members == null) {
                    continue;
                }
                for (String id : members) {
                    var driver = UUID.fromString(id);
                    var last = last(driver).orElse(null);
                    // the set can lag the hash by one move; trust the hash
                    if (last != null && last.at().isAfter(freshAfter) && cells.cell(last.position(), res) == c) {
                        out.add(new Nearby(driver, last.position(), last.at(), ring));
                    }
                }
            }
        }
        out.sort(Comparator.comparingInt(Nearby::ring));
        return out;
    }

    private String cellKey(long cell) {
        return "rm:cell:" + res + ":" + cell;
    }
}
