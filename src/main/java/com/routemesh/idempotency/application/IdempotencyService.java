package com.routemesh.idempotency.application;

import com.routemesh.shared.error.ApiException;
import com.routemesh.shared.security.Caller;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Replays the stored response for a repeated (scope, caller, key). The key is claimed before the action runs,
 * so two concurrent requests with one key can't both act: the loser gets 409 and can retry for the replay.
 * Reusing a key for a different request body is 422. If the action fails the claim is released, so a client
 * can retry a declined payment with the same key once the cause is fixed.
 */
@Service
public class IdempotencyService {

    private static final Duration STALE = Duration.ofMinutes(2);

    private final IdempotencyStore store;
    private final ObjectMapper json;
    private final Clock clock;

    public IdempotencyService(IdempotencyStore store, ObjectMapper json, Clock clock) {
        this.store = store;
        // map keys sorted so a request hashes the same on every JVM
        this.json = json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.clock = clock;
    }

    public <T> Idempotent<T> run(String scope, Caller caller, String key, Object request, Class<T> type,
                                 Supplier<T> action) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw ApiException.invalid("idempotency-key-required", "send an Idempotency-Key header (1-100 chars)");
        }
        String hash = hash(request);
        var now = clock.instant();
        if (!store.claim(scope, caller.subject(), key, hash, now)) {
            var existing = store.find(scope, caller.subject(), key)
                    .orElseThrow(() -> ApiException.conflict("idempotency-in-progress", "retry the request"));
            if (!existing.requestHash().equals(hash)) {
                throw ApiException.unprocessable("idempotency-key-reused",
                        "this Idempotency-Key was used for a different request");
            }
            if (existing.done()) {
                return new Idempotent<>(read(existing.responseBody(), type), true);
            }
            if (!store.takeOver(scope, caller.subject(), key, hash, now.minus(STALE), now)) {
                throw ApiException.conflict("idempotency-in-progress",
                        "a request with this Idempotency-Key is still running; retry shortly");
            }
        }
        T result;
        try {
            result = action.get();
        } catch (RuntimeException e) {
            store.release(scope, caller.subject(), key);
            throw e;
        }
        store.complete(scope, caller.subject(), key, write(result));
        return new Idempotent<>(result, false);
    }

    private String hash(Object request) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(write(request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise " + o.getClass(), e);
        }
    }

    private <T> T read(String body, Class<T> type) {
        try {
            return json.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored response is not a " + type.getSimpleName(), e);
        }
    }
}
