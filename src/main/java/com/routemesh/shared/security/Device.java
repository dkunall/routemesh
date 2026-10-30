package com.routemesh.shared.security;

import java.util.UUID;

/** An authenticated driver device. Every driver call names the device, so events can be sequenced per device. */
public record Device(UUID deviceId, UUID driverId) {

    public void requireDriver(UUID driver) {
        if (!driverId.equals(driver)) {
            throw com.routemesh.shared.error.ApiException.forbidden("forbidden", "This device belongs to another driver");
        }
    }
}
