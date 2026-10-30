package com.routemesh.shipment.adapter;

import com.routemesh.idempotency.adapter.IdempotentResponses;
import com.routemesh.shared.error.ApiException;
import com.routemesh.shared.security.Caller;
import com.routemesh.shipment.application.ShipmentService;
import com.routemesh.shipment.application.ShipmentService.NewShipment;
import com.routemesh.shipment.application.ShipmentService.ShipmentView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
class ShipmentController {

    private final ShipmentService shipments;

    ShipmentController(ShipmentService shipments) {
        this.shipments = shipments;
    }

    @PostMapping("/v1/shipments")
    ResponseEntity<ShipmentView> create(Caller caller,
                                        @RequestHeader(name = IdempotentResponses.KEY_HEADER, required = false) String key,
                                        @RequestBody NewShipment in) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw ApiException.invalid("idempotency-key-required", "send an Idempotency-Key header (1..100 chars)");
        }
        if (in.hubId() == null || in.reference() == null || in.kind() == null || in.serviceDate() == null
                || in.windowStart() == null || in.windowEnd() == null || in.recipientEmail() == null
                || in.recipientName() == null) {
            throw ApiException.invalid("hubId, reference, kind, recipientName, recipientEmail, serviceDate, "
                    + "windowStart and windowEnd are required");
        }
        return IdempotentResponses.of(shipments.create(caller, key, in), HttpStatus.CREATED);
    }

    @GetMapping("/v1/shipments/{id}")
    ShipmentView get(Caller caller, @PathVariable UUID id) {
        return shipments.get(caller, id);
    }

    @PostMapping("/v1/shipments/{id}/cancel")
    ShipmentView cancel(Caller caller, @PathVariable UUID id) {
        return shipments.cancel(caller, id);
    }
}
