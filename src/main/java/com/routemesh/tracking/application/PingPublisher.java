package com.routemesh.tracking.application;

import com.routemesh.tracking.domain.Ping;

import java.util.List;
import java.util.UUID;

/** Hands a batch of fixes to the stream, keyed by driver so one driver's batches stay in order. */
public interface PingPublisher {

    String TOPIC = "routemesh.pings";

    void publish(UUID driverId, List<Ping> pings);
}
