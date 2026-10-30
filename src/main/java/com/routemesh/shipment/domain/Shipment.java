package com.routemesh.shipment.domain;

import com.routemesh.geo.domain.GeoPoint;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * @param windowStart the start of the customer's promised slot on {@code serviceDate}
 * @param attempts    failed attempts so far
 * @param version     optimistic lock; every change bumps it
 */
public record Shipment(UUID id, UUID hubId, String shipperSubject, String reference, ShipmentKind kind,
                       GeoPoint drop, String address, String recipientName, String recipientEmail,
                       String recipientPhone, LocalDate serviceDate, Instant windowStart, Instant windowEnd,
                       int weightKg, ShipmentStatus status, int attempts, UUID routeId, String trackingToken,
                       long version, Instant createdAt) {

    public Shipment withStatus(ShipmentStatus next, int attempts) {
        return new Shipment(id, hubId, shipperSubject, reference, kind, drop, address, recipientName, recipientEmail,
                recipientPhone, serviceDate, windowStart, windowEnd, weightKg, next, attempts, routeId, trackingToken,
                version, createdAt);
    }

    public Shipment withRoute(UUID route) {
        return new Shipment(id, hubId, shipperSubject, reference, kind, drop, address, recipientName, recipientEmail,
                recipientPhone, serviceDate, windowStart, windowEnd, weightKg, status, attempts, route, trackingToken,
                version, createdAt);
    }

    public Shipment withSlot(LocalDate date, Instant start, Instant end) {
        return new Shipment(id, hubId, shipperSubject, reference, kind, drop, address, recipientName, recipientEmail,
                recipientPhone, date, start, end, weightKg, status, attempts, routeId, trackingToken, version,
                createdAt);
    }

    /** Phone shown to drivers: last three digits only. */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) {
            return "***";
        }
        return "*".repeat(phone.length() - 3) + phone.substring(phone.length() - 3);
    }
}
