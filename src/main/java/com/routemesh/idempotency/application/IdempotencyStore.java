package com.routemesh.idempotency.application;

import java.time.Instant;
import java.util.Optional;

/** Each call runs in its own short transaction, never inside the business transaction it guards. */
public interface IdempotencyStore {

    /** Inserts an IN_PROGRESS claim on the key; false if a record already exists. */
    boolean claim(String scope, String subject, String key, String requestHash, Instant now);

    Optional<Existing> find(String scope, String subject, String key);

    /** Takes over an IN_PROGRESS record older than {@code staleBefore} (its owner crashed). */
    boolean takeOver(String scope, String subject, String key, String requestHash, Instant staleBefore, Instant now);

    void complete(String scope, String subject, String key, String responseBody);

    void release(String scope, String subject, String key);

    record Existing(String requestHash, boolean done, String responseBody, Instant createdAt) {
    }
}
