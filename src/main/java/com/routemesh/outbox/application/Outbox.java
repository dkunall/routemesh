package com.routemesh.outbox.application;

import java.util.Map;

/**
 * Port for events that must leave the process exactly when the state change commits. Implementations write to
 * the outbox table in the caller's transaction; a relay publishes them afterwards.
 */
public interface Outbox {

    /** Shipment lifecycle changes, keyed by shipment id. Feeds the tracking read model and notifications. */
    String SHIPMENT_EVENTS = "routemesh.shipment-events";

    void append(String topic, String key, String type, Map<String, Object> data);
}
