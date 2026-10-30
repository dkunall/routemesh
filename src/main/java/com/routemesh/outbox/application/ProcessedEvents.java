package com.routemesh.outbox.application;

import java.util.UUID;

/** Consumer-side dedupe: Kafka delivers at least once, so every consumer records what it has handled. */
public interface ProcessedEvents {

    /** False if this consumer has already handled the event. Call inside the consumer's transaction. */
    boolean markProcessed(String consumer, UUID eventId);
}
