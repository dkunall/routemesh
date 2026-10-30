package com.routemesh.shared.security;

/**
 * Shippers create shipments and see their own. Dispatchers run the fleet: hubs, vehicles, drivers, planning.
 * Drivers are not JWT callers at all; their devices authenticate with a per-device token (see {@link DeviceAuth}).
 */
public enum Role {
    SHIPPER,
    DISPATCHER
}
