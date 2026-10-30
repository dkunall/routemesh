package com.routemesh.idempotency.adapter;

import com.routemesh.idempotency.application.Idempotent;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** First call answers {@code created}; a replay answers 200 with {@code Idempotent-Replayed: true}. */
public final class IdempotentResponses {

    public static final String KEY_HEADER = "Idempotency-Key";

    private IdempotentResponses() {
    }

    public static <T> ResponseEntity<T> of(Idempotent<T> r, HttpStatus created) {
        if (r.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(r.value());
        }
        return ResponseEntity.status(created).body(r.value());
    }
}
