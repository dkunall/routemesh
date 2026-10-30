package com.routemesh.tracking.adapter;

import com.routemesh.tracking.application.RateLimiter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

@Component
class RedisRateLimiter implements RateLimiter {

    private final StringRedisTemplate redis;
    private final Clock clock;

    RedisRateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public boolean allow(String bucket, int limit, Duration window) {
        long slot = clock.millis() / window.toMillis();
        String key = "rm:rl:" + bucket + ":" + slot;
        Long n = redis.opsForValue().increment(key);
        if (n != null && n == 1) {
            redis.expire(key, window.plusSeconds(5));
        }
        return n != null && n <= limit;
    }
}
