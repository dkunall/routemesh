package com.routemesh.tracking.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TrackingEventStore {

    record EventIn(long deviceSeq, UUID shipmentId, String status, String reason, Double lat, Double lng,
                   Instant deviceAt) {
    }

    record Stored(long id, long deviceSeq, UUID shipmentId, String status, String reason, Double lat, Double lng,
                  String state, String outcome, Instant receivedAt) {
    }

    /** Locks the device row and returns its cursor (last applied sequence number). */
    long lockDevice(UUID deviceId);

    void setCursor(UUID deviceId, long seq);

    /** False when this (device, seq) already exists. */
    boolean insert(UUID deviceId, EventIn e, String state, String outcome, Instant now);

    Optional<Stored> find(UUID deviceId, long seq);

    /** Every event of the device above {@code seq}, in sequence order. */
    List<Stored> after(UUID deviceId, long seq);

    void outcome(long id, String state, String outcome, Instant now);

    /** Devices whose oldest pending event has waited since before {@code before}. */
    List<UUID> waitingSince(Instant before);

    List<Stored> forShipment(UUID shipmentId);
}
