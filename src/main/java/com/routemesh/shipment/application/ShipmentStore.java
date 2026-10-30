package com.routemesh.shipment.application;

import com.routemesh.shipment.domain.Shipment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShipmentStore {

    void insert(Shipment s, double fenceMeters);

    Optional<Shipment> find(UUID id);

    /** Row-locks the shipment for the rest of the transaction. */
    Optional<Shipment> lock(UUID id);

    List<Shipment> lockAll(Collection<UUID> ids);

    Optional<Shipment> byToken(String token);

    /** Writes status, attempts, route and slot if the version still matches; bumps it. False on a lost race. */
    boolean update(Shipment s, Instant now);

    List<Shipment> plannable(UUID hubId, LocalDate date);

    List<Shipment> onRoute(UUID routeId);

    void setOtp(UUID id, String hash);

    Optional<String> otpHash(UUID id);

    /** Counts a wrong code; returns failures so far. */
    int otpFailed(UUID id);

    void deactivateFence(UUID shipmentId);
}
