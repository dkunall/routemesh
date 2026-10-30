package com.routemesh.tracking.application;

import com.routemesh.geo.application.DriverIndex;
import com.routemesh.geo.domain.CellIndex;
import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.geo.domain.Haversine;
import com.routemesh.outbox.application.Outbox;
import com.routemesh.routing.application.RouteRow;
import com.routemesh.routing.application.RouteStore;
import com.routemesh.routing.domain.TravelModel;
import com.routemesh.shared.config.RouteMeshProperties;
import com.routemesh.shipment.application.ShipmentEvents;
import com.routemesh.shipment.application.ShipmentLifecycle;
import com.routemesh.shipment.application.ShipmentStore;
import com.routemesh.shipment.domain.Shipment;
import com.routemesh.shipment.domain.ShipmentKind;
import com.routemesh.shipment.domain.ShipmentStatus;
import com.routemesh.tracking.application.TrackingStores.AlertStore;
import com.routemesh.tracking.application.TrackingStores.Fence;
import com.routemesh.tracking.application.TrackingStores.GeofenceStore;
import com.routemesh.tracking.application.TrackingStores.PingStore;
import com.routemesh.tracking.application.TrackingStores.SpeedStore;
import com.routemesh.tracking.application.TrackingStores.ViewStore;
import com.routemesh.tracking.domain.LearnedEta;
import com.routemesh.geo.domain.Ping;
import com.routemesh.tracking.domain.PingFilter;
import com.routemesh.tracking.domain.SlaRisk;
import com.routemesh.tracking.domain.StaticEta;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consumes one driver's batch of fixes: drops junk, stores a simplified track, moves the driver in the cell
 * index, learns speeds, fires geofence transitions (hub exit / hub return / arrival at a drop), refreshes the ETA
 * of every remaining stop and raises an SLA alert when a window is about to be missed.
 */
@Service
public class PingProcessor {

    private static final Logger log = LoggerFactory.getLogger(PingProcessor.class);
    /** Consecutive fixes further apart than this are not used for speed: the phone may have been off. */
    private static final Duration MAX_SPEED_GAP = Duration.ofSeconds(120);
    private static final Duration MIN_SPEED_GAP = Duration.ofSeconds(5);
    private static final double MIN_MOVE_KM = 0.005;

    private final PingFilter filter;
    private final PingStore pings;
    private final SpeedStore speeds;
    private final GeofenceStore fences;
    private final AlertStore alerts;
    private final ViewStore views;
    private final DriverIndex drivers;
    private final CellIndex cells;
    private final RouteStore routes;
    private final ShipmentStore shipments;
    private final ShipmentLifecycle lifecycle;
    private final Outbox outbox;
    private final RouteMeshProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry meters;
    private final LearnedEta eta;

    public PingProcessor(PingStore pings, SpeedStore speeds, GeofenceStore fences, AlertStore alerts,
                         ViewStore views, DriverIndex drivers, CellIndex cells, RouteStore routes,
                         ShipmentStore shipments, ShipmentLifecycle lifecycle, Outbox outbox,
                         RouteMeshProperties props, TransactionTemplate tx, Clock clock, MeterRegistry meters) {
        var t = props.tracking();
        this.filter = new PingFilter(t.maxSpeedKmh(), t.simplifyMeters(), t.maxPingAge());
        this.pings = pings;
        this.speeds = speeds;
        this.fences = fences;
        this.alerts = alerts;
        this.views = views;
        this.drivers = drivers;
        this.cells = cells;
        this.routes = routes;
        this.shipments = shipments;
        this.lifecycle = lifecycle;
        this.outbox = outbox;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
        this.meters = meters;
        var travel = new TravelModel(props.planning().roadFactor(), props.planning().planningKmh());
        this.eta = new LearnedEta(cells, speeds, new StaticEta(travel), t.h3Resolution());
    }

    public void process(UUID driverId, List<Ping> batch) {
        var previous = drivers.last(driverId).orElse(null);
        var r = filter.apply(batch, previous, clock.instant());
        meters.counter("routemesh.pings", "result", "duplicate").increment(r.duplicates());
        meters.counter("routemesh.pings", "result", "stale").increment(r.stale());
        meters.counter("routemesh.pings", "result", "outlier").increment(r.outliers());
        meters.counter("routemesh.pings", "result", "accepted").increment(r.accepted().size());
        log.debug("driver {}: {} pings, {} accepted, {} stale, {} duplicate, {} outlier", driverId, batch.size(),
                r.accepted().size(), r.stale(), r.duplicates(), r.outliers());
        if (r.accepted().isEmpty()) {
            return;
        }
        pings.save(driverId, r.stored());
        learn(previous, r.accepted());
        var last = r.last();
        drivers.update(driverId, last);
        tx.executeWithoutResult(s -> {
            var route = routes.activeForDriver(driverId).orElse(null);
            geofences(driverId, last, route);
            if (route != null) {
                refreshEtas(route, last);
            }
        });
    }

    private void learn(Ping previous, List<Ping> accepted) {
        var track = new ArrayList<Ping>();
        if (previous != null) {
            track.add(previous);
        }
        track.addAll(accepted);
        var zone = props.planning().zone();
        double alpha = props.tracking().ewmaAlpha();
        for (int i = 1; i < track.size(); i++) {
            var a = track.get(i - 1);
            var b = track.get(i);
            var dt = Duration.between(a.at(), b.at());
            double km = Haversine.km(a.position(), b.position());
            if (dt.compareTo(MIN_SPEED_GAP) < 0 || dt.compareTo(MAX_SPEED_GAP) > 0 || km < MIN_MOVE_KM) {
                continue;
            }
            double kmh = km / (dt.toMillis() / 3_600_000.0);
            var mid = new GeoPoint((a.position().lat() + b.position().lat()) / 2,
                    (a.position().lng() + b.position().lng()) / 2);
            int hour = a.at().atZone(zone).getHour();
            var now = clock.instant();
            speeds.record(cells.cell(mid, props.tracking().h3Resolution()), hour, kmh, alpha, now);
            speeds.record(SpeedStore.AREA, hour, kmh, alpha, now);
        }
    }

    private void geofences(UUID driverId, Ping last, RouteRow route) {
        var inside = fences.containing(last.position());
        var before = fences.presence(driverId);
        var now = new HashSet<UUID>();
        for (Fence f : inside) {
            now.add(f.id());
            if (!before.contains(f.id())) {
                fences.enter(driverId, f.id(), last.at());
                meters.counter("routemesh.geofence", "kind", f.kind(), "edge", "enter").increment();
                if (route != null) {
                    entered(f, route, last.at());
                }
            }
        }
        for (UUID id : before) {
            if (!now.contains(id)) {
                fences.exit(driverId, id);
                var f = fences.fence(id).orElse(null);
                if (f != null && route != null && f.kind().equals("HUB") && f.refId().equals(route.hubId())) {
                    meters.counter("routemesh.geofence", "kind", "HUB", "edge", "exit").increment();
                    leftHub(route);
                }
            }
        }
    }

    private void entered(Fence f, RouteRow route, Instant at) {
        if (f.kind().equals("DROP")) {
            var s = shipments.find(f.refId()).orElse(null);
            if (s != null && route.id().equals(s.routeId())) {
                routes.arrived(s.id(), at);
            }
        } else if (f.refId().equals(route.hubId())) {
            backAtHub(route);
        }
    }

    /** Leaving the hub with parcels scanned on board puts them in transit. */
    private void leftHub(RouteRow route) {
        for (var s : shipments.onRoute(route.id())) {
            if (s.kind() == ShipmentKind.DELIVERY && s.status() == ShipmentStatus.PICKED_UP) {
                shipments.lock(s.id()).ifPresent(l -> lifecycle.move(l, ShipmentStatus.IN_TRANSIT, "hub-exit", "geofence"));
            }
        }
    }

    /** Back at the hub: returns are handed in, undelivered parcels are back, and an empty route is done. */
    private void backAtHub(RouteRow route) {
        boolean open = false;
        for (var s : shipments.onRoute(route.id())) {
            var locked = shipments.lock(s.id()).orElseThrow();
            if (locked.kind() == ShipmentKind.DELIVERY && locked.status() == ShipmentStatus.RTO) {
                lifecycle.move(locked, ShipmentStatus.RETURNED, "back-at-hub", "geofence");
            } else if (locked.kind() == ShipmentKind.RETURN && locked.status() == ShipmentStatus.PICKED_UP) {
                lifecycle.move(locked, ShipmentStatus.DELIVERED, "handed-in-at-hub", "geofence");
            } else if (locked.status().awaitingVisit(locked.kind())) {
                open = true;
            }
        }
        if (!open) {
            routes.complete(route.id(), clock.instant());
            meters.counter("routemesh.route.completed").increment();
        }
    }

    private void refreshEtas(RouteRow route, Ping last) {
        var byId = new java.util.HashMap<UUID, Shipment>();
        shipments.onRoute(route.id()).forEach(s -> byId.put(s.id(), s));
        var zone = props.planning().zone();
        var margin = props.tracking().slaMargin();
        Instant t = last.at();
        GeoPoint pos = last.position();
        var stops = routes.stops(route.id()).stream().sorted(Comparator.comparingInt(RouteStore.Stop::seq)).toList();
        for (var stop : stops) {
            var s = byId.get(stop.shipmentId());
            if (s == null || !s.status().awaitingVisit(s.kind())) {
                continue;
            }
            double minutes = eta.minutes(pos, s.drop(), t.atZone(zone).getHour());
            Instant arrive = t.plusSeconds(Math.round(minutes * 60));
            routes.predicted(route.id(), s.id(), arrive);
            views.eta(s.id(), arrive);
            if (SlaRisk.atRisk(arrive, s.windowEnd(), margin)
                    && alerts.raise("SLA_RISK", s.id(), route.id(), "predicted " + arrive + " vs window end "
                    + s.windowEnd(), clock.instant())) {
                outbox.append(Outbox.SHIPMENT_EVENTS, s.id().toString(), ShipmentEvents.ALERT,
                        Map.of("kind", "SLA_RISK", "shipmentId", s.id().toString(), "routeId", route.id().toString(),
                                "predictedEta", arrive.toString()));
                meters.counter("routemesh.alerts", "kind", "SLA_RISK").increment();
                log.info("sla risk shipment={} route={}", s.id(), route.id());
            }
            Instant start = arrive.isBefore(s.windowStart()) ? s.windowStart() : arrive;
            t = start.plus(Duration.ofMinutes(props.planning().serviceMinutes()));
            pos = s.drop();
        }
    }
}
