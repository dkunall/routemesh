package com.routemesh.tracking.adapter;

import com.routemesh.tracking.application.DeviceEventService;
import com.routemesh.tracking.application.TrackingStores.PingStore;
import com.routemesh.tracking.application.TrackingStores.ViewStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

@Component
@ConditionalOnProperty(name = "routemesh.jobs.enabled", havingValue = "true", matchIfMissing = true)
class TrackingJobs {

    private static final Logger log = LoggerFactory.getLogger(TrackingJobs.class);

    private final DeviceEventService events;
    private final PingStore pings;
    private final ViewStore views;
    private final Clock clock;
    private final Duration pingRetention;

    TrackingJobs(DeviceEventService events, PingStore pings, ViewStore views, Clock clock,
                 @Value("${routemesh.retention.pings:P30D}") Duration pingRetention) {
        this.events = events;
        this.pings = pings;
        this.views = views;
        this.clock = clock;
        this.pingRetention = pingRetention;
    }

    /** Skips sequence numbers a phone never sent, once they have been missing for gapWait. */
    @Scheduled(fixedDelayString = "PT2S")
    void gaps() {
        for (var device : events.devicesWaiting()) {
            int applied = events.skipGap(device);
            if (applied > 0) {
                log.info("device={} skipped a sequence gap, applied {} waiting events", device, applied);
            }
        }
    }

    /** Raw tracks are kept for a bounded time; a delivered parcel's page disappears after its TTL. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    void retention() {
        var now = clock.instant();
        int p = pings.purgeBefore(now.minus(pingRetention));
        int v = views.purgeExpired(now);
        if (p + v > 0) {
            log.info("retention removed {} pings and {} expired tracking pages", p, v);
        }
    }
}
