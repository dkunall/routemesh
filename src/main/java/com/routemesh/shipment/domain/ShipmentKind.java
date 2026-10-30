package com.routemesh.shipment.domain;

/** DELIVERY is loaded at the hub and dropped at the customer; RETURN is collected from the customer for the hub. */
public enum ShipmentKind {
    DELIVERY,
    RETURN
}
