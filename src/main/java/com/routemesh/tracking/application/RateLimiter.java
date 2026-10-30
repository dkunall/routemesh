package com.routemesh.tracking.application;

import java.time.Duration;

/** Fixed-window counter for unauthenticated endpoints. */
public interface RateLimiter {

    /** True if this call is within {@code limit} for the current window of {@code window}. */
    boolean allow(String bucket, int limit, Duration window);
}
