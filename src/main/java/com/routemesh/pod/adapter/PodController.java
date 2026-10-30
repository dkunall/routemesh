package com.routemesh.pod.adapter;

import com.routemesh.pod.application.PodService;
import com.routemesh.shared.security.Device;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
class PodController {

    private final PodService pods;

    PodController(PodService pods) {
        this.pods = pods;
    }

    /** Photo (base64 JPEG/PNG), the customer's OTP and where the driver stands. Same photo again is a replay. */
    @PostMapping("/v1/shipments/{shipmentId}/pod")
    PodService.PodView submit(Device device, @PathVariable UUID shipmentId, @RequestBody PodService.PodIn in) {
        return pods.submit(device, shipmentId, in);
    }
}
