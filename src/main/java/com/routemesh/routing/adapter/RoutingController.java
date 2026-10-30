package com.routemesh.routing.adapter;

import com.routemesh.routing.application.RoutingService;
import com.routemesh.routing.application.RoutingService.DispatchResult;
import com.routemesh.routing.application.RoutingService.PlanResult;
import com.routemesh.routing.application.RoutingService.RouteView;
import com.routemesh.shared.error.ApiException;
import com.routemesh.shared.security.Caller;
import com.routemesh.shared.security.Device;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
class RoutingController {

    record PlanIn(LocalDate serviceDate) {
    }

    record InsertIn(UUID shipmentId, Long expectedVersion) {
    }

    record DispatchIn(UUID driverId, Long expectedVersion) {
    }

    record AutoIn(UUID shipmentId) {
    }

    private final RoutingService routing;

    RoutingController(RoutingService routing) {
        this.routing = routing;
    }

    @PostMapping("/v1/hubs/{hubId}/plans")
    PlanResult plan(Caller caller, @PathVariable UUID hubId, @RequestBody PlanIn in) {
        if (in.serviceDate() == null) {
            throw ApiException.invalid("serviceDate is required");
        }
        return routing.plan(caller, hubId, in.serviceDate());
    }

    @GetMapping("/v1/routes/{id}")
    RouteView get(Caller caller, @PathVariable UUID id) {
        return routing.get(caller, id);
    }

    /** Insert one shipment into a known route; expectedVersion guards against a concurrent change. */
    @PostMapping("/v1/routes/{id}/insertions")
    RouteView insert(Caller caller, @PathVariable UUID id, @RequestBody InsertIn in) {
        if (in.shipmentId() == null || in.expectedVersion() == null) {
            throw ApiException.invalid("shipmentId and expectedVersion are required");
        }
        return routing.insert(caller, id, in.shipmentId(), in.expectedVersion());
    }

    @PostMapping("/v1/routes/{id}/dispatch")
    RouteView dispatch(Caller caller, @PathVariable UUID id, @RequestBody DispatchIn in) {
        if (in.driverId() == null || in.expectedVersion() == null) {
            throw ApiException.invalid("driverId and expectedVersion are required");
        }
        return routing.dispatch(caller, id, in.driverId(), in.expectedVersion());
    }

    /** Picks the route (planned, or on the road nearby for a return) where the shipment adds the fewest km. */
    @PostMapping("/v1/dispatch:auto")
    DispatchResult auto(Caller caller, @RequestBody AutoIn in) {
        if (in.shipmentId() == null) {
            throw ApiException.invalid("shipmentId is required");
        }
        return routing.autoDispatch(caller, in.shipmentId());
    }

    @GetMapping("/v1/drivers/{driverId}/route")
    RouteView driverRoute(Device device, @PathVariable UUID driverId) {
        device.requireDriver(driverId);
        return routing.driverRoute(driverId);
    }
}
