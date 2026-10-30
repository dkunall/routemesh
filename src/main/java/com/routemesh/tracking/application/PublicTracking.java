package com.routemesh.tracking.application;

import com.routemesh.geo.application.DriverIndex;
import com.routemesh.geo.domain.CellIndex;
import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.shared.config.RouteMeshProperties;
import com.routemesh.shared.error.ApiException;
import com.routemesh.tracking.application.TrackingStores.ViewStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * What the customer sees behind the tracking link. Reads only the read model and the live index. The driver's
 * position is shown only while the parcel is out for delivery, and only as the centre of a coarse H3 cell
 * (resolution 8, about 0.5 km across), so the link does not become a way to follow a driver around.
 */
@Service
public class PublicTracking {

    public record TrackingPage(String reference, String status, Instant windowStart, Instant windowEnd, Instant eta,
                               int attempts, String driverName, GeoPoint driverArea, Instant driverSeenAt,
                               Instant deliveredAt, boolean canReschedule) {
    }

    static final int PUBLIC_RESOLUTION = 8;
    private static final Duration FRESH = Duration.ofMinutes(10);

    private final ViewStore views;
    private final DriverIndex drivers;
    private final CellIndex cells;
    private final Clock clock;

    public PublicTracking(ViewStore views, DriverIndex drivers, CellIndex cells, RouteMeshProperties props,
                          Clock clock) {
        this.views = views;
        this.drivers = drivers;
        this.cells = cells;
        this.clock = clock;
    }

    public TrackingPage page(String token) {
        var v = views.byToken(token).orElseThrow(() -> ApiException.notFound("tracking link"));
        var now = clock.instant();
        if (v.expiresAt() != null && v.expiresAt().isBefore(now)) {
            throw new ApiException(410, "expired", "this tracking link has expired");
        }
        GeoPoint area = null;
        Instant seen = null;
        if (v.status().equals("OUT_FOR_DELIVERY") && v.driverId() != null) {
            var last = drivers.last(v.driverId()).filter(p -> p.at().isAfter(now.minus(FRESH))).orElse(null);
            if (last != null) {
                area = cells.center(cells.cell(last.position(), PUBLIC_RESOLUTION));
                seen = last.at();
            }
        }
        boolean canReschedule = v.status().equals("CREATED") || v.status().equals("FAILED_ATTEMPT");
        return new TrackingPage(v.reference(), v.status(), v.windowStart(), v.windowEnd(), v.eta(), v.attempts(),
                v.driverName(), area, seen, v.deliveredAt(), canReschedule);
    }
}
