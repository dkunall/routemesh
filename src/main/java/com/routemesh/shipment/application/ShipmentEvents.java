package com.routemesh.shipment.application;

/** Event types on {@link com.routemesh.outbox.application.Outbox#SHIPMENT_EVENTS}. Payloads carry ids, not PII. */
public final class ShipmentEvents {

    public static final String CREATED = "shipment.created";
    public static final String STATUS_CHANGED = "shipment.status-changed";
    public static final String RESCHEDULED = "shipment.rescheduled";
    public static final String ALERT = "alert.raised";

    private ShipmentEvents() {
    }
}
