package com.routemesh.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties("routemesh")
public record RouteMeshProperties(Security security, Mail mail, Planning planning, Tracking tracking,
                                  Delivery delivery, Jobs jobs) {

    /**
     * @param dispatcherSubjects JWT subjects that get the DISPATCHER role
     * @param otpKey             HMAC key for delivery OTP hashes (never logged)
     * @param publicBaseUrl      base of the customer tracking link put into mails
     */
    public record Security(List<String> dispatcherSubjects, String otpKey, String publicBaseUrl) {
    }

    public record Mail(String from, String opsAddress) {
    }

    /**
     * @param roadFactor   multiplier from straight-line to road distance (no road graph here, see README)
     * @param planningKmh  speed used when a route is planned, before any pings exist
     * @param budget       time budget for local search on a full plan
     * @param insertBudget time budget for 2-opt after an incremental insert
     */
    public record Planning(double roadFactor, double planningKmh, Duration budget, Duration insertBudget,
                           int serviceMinutes, int candidateRing) {
    }

    /**
     * @param h3Resolution    resolution for driver positions and speed segments
     * @param ewmaAlpha       weight of a new speed sample
     * @param maxSpeedKmh     pings implying more than this from the previous one are dropped as GPS jumps
     * @param simplifyMeters  Douglas-Peucker tolerance for stored tracks
     * @param maxPingAge      pings older than this on arrival are dropped instead of queued
     * @param slaMargin       predicted arrival within this of window end raises an SLA risk alert
     */
    public record Tracking(int h3Resolution, double ewmaAlpha, double maxSpeedKmh, double simplifyMeters,
                           Duration maxPingAge, Duration slaMargin, Duration gapWait) {
    }

    /**
     * @param maxAttempts     failed attempts before the shipment returns to origin
     * @param podRadiusMeters proof-of-delivery location must be within this of the drop
     * @param fenceMeters     geofence radius around a drop, for automatic arrival
     */
    public record Delivery(int maxAttempts, double podRadiusMeters, double fenceMeters, double hubFenceMeters,
                           int maxPhotoBytes, Duration trackingTtlAfterDelivery) {
    }

    public record Jobs(boolean enabled) {
    }
}
