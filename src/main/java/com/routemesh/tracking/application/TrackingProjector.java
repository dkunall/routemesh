package com.routemesh.tracking.application;

import com.routemesh.fleet.application.FleetStore;
import com.routemesh.fleet.domain.Driver;
import com.routemesh.outbox.application.EventEnvelope;
import com.routemesh.outbox.application.ProcessedEvents;
import com.routemesh.routing.application.RouteStore;
import com.routemesh.shared.config.RouteMeshProperties;
import com.routemesh.shipment.application.ShipmentEvents;
import com.routemesh.shipment.application.ShipmentStore;
import com.routemesh.shipment.domain.ShipmentStatus;
import com.routemesh.tracking.application.TrackingStores.View;
import com.routemesh.tracking.application.TrackingStores.ViewStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Keeps the public tracking read model in step with shipment events. Events carry ids only; details are read
 * from the shipment row, so the view is always the latest state even if events arrive late. Dedupe by event id,
 * and the upsert refuses to overwrite a row written from a later event.
 */
@Service
public class TrackingProjector {

    static final String CONSUMER = "tracking-projector";

    private final ProcessedEvents processed;
    private final ShipmentStore shipments;
    private final RouteStore routes;
    private final FleetStore fleet;
    private final ViewStore views;
    private final RouteMeshProperties props;

    public TrackingProjector(ProcessedEvents processed, ShipmentStore shipments, RouteStore routes, FleetStore fleet,
                             ViewStore views, RouteMeshProperties props) {
        this.processed = processed;
        this.shipments = shipments;
        this.routes = routes;
        this.fleet = fleet;
        this.views = views;
        this.props = props;
    }

    @Transactional
    public boolean handle(EventEnvelope e) {
        if (e.type().equals(ShipmentEvents.ALERT) || !processed.markProcessed(CONSUMER, e.eventId())) {
            return false;
        }
        var id = UUID.fromString((String) e.data().get("shipmentId"));
        var s = shipments.find(id).orElse(null);
        if (s == null) {
            return false;
        }
        UUID driverId = s.routeId() == null ? null
                : routes.find(s.routeId()).map(r -> r.driverId()).orElse(null);
        String driverName = driverId == null ? null : fleet.driver(driverId).map(Driver::publicName).orElse(null);
        boolean done = s.status().terminal();
        var deliveredAt = s.status() == ShipmentStatus.DELIVERED ? e.at() : null;
        var expires = done ? e.at().plus(props.delivery().trackingTtlAfterDelivery()) : null;
        views.upsert(new View(s.id(), s.trackingToken(), s.reference(), s.status().name(), s.windowStart(),
                s.windowEnd(), null, s.attempts(), driverName, driverId, e.at(), deliveredAt, expires));
        return true;
    }
}
