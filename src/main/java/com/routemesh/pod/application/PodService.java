package com.routemesh.pod.application;

import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.routing.application.RouteStore;
import com.routemesh.shared.config.RouteMeshProperties;
import com.routemesh.shared.error.ApiException;
import com.routemesh.shared.security.Device;
import com.routemesh.shipment.application.ShipmentLifecycle;
import com.routemesh.shipment.application.ShipmentService;
import com.routemesh.shipment.application.ShipmentStore;
import com.routemesh.shipment.domain.ShipmentKind;
import com.routemesh.shipment.domain.ShipmentStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Proof of delivery: the customer's one-time code, a photo, and the phone's position, which must be within
 * {@code podRadiusMeters} of the address. A wrong code is counted even though the request fails, so the
 * transaction commits first and the error is thrown afterwards. Resending the same proof is answered from what
 * was stored.
 */
@Service
public class PodService {

    public record PodIn(String otp, Double lat, Double lng, String photoBase64, String photoType) {
    }

    public record PodView(UUID shipmentId, String status, java.time.Instant deliveredAt, double distanceMeters,
                          String photoSha256, boolean replayed) {
    }

    private sealed interface Outcome {
    }

    private record Done(PodView view) implements Outcome {
    }

    private record Refused(ApiException error) implements Outcome {
    }

    private final ShipmentStore shipments;
    private final ShipmentService shipmentService;
    private final ShipmentLifecycle lifecycle;
    private final RouteStore routes;
    private final PodStore pods;
    private final RouteMeshProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry meters;

    public PodService(ShipmentStore shipments, ShipmentService shipmentService, ShipmentLifecycle lifecycle,
                      RouteStore routes, PodStore pods, RouteMeshProperties props, TransactionTemplate tx, Clock clock,
                      MeterRegistry meters) {
        this.shipments = shipments;
        this.shipmentService = shipmentService;
        this.lifecycle = lifecycle;
        this.routes = routes;
        this.pods = pods;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
        this.meters = meters;
    }

    public PodView submit(Device device, UUID shipmentId, PodIn in) {
        if (in.otp() == null || !in.otp().matches("\\d{6}")) {
            throw ApiException.invalid("bad-otp-format", "otp must be 6 digits");
        }
        if (in.lat() == null || in.lng() == null) {
            throw ApiException.invalid("position-required", "lat and lng are required");
        }
        GeoPoint at;
        try {
            at = new GeoPoint(in.lat(), in.lng());
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("bad-position", e.getMessage());
        }
        byte[] photo = photo(in);
        String sha = sha256(photo);
        var outcome = tx.execute(t -> decide(device, shipmentId, in, at, photo, sha));
        meters.counter("routemesh.pod", "result", outcome instanceof Done d && !d.view().replayed() ? "delivered"
                : outcome instanceof Done ? "replayed" : ((Refused) outcome).error().code()).increment();
        return switch (outcome) {
            case Done d -> d.view();
            case Refused r -> throw r.error();
        };
    }

    private Outcome decide(Device device, UUID shipmentId, PodIn in, GeoPoint at, byte[] photo, String sha) {
        var s = shipments.lock(shipmentId).orElseThrow(() -> ApiException.notFound("shipment"));
        var route = s.routeId() == null ? null : routes.find(s.routeId()).orElse(null);
        if (route == null || !device.driverId().equals(route.driverId())) {
            throw ApiException.notFound("shipment");
        }
        if (s.kind() != ShipmentKind.DELIVERY) {
            throw ApiException.unprocessable("not-a-delivery", "returns are collected with a PICKED_UP event");
        }
        if (s.status() == ShipmentStatus.DELIVERED) {
            var existing = pods.find(s.id()).orElseThrow();
            if (existing.photoSha256().equals(sha)) {
                return new Done(new PodView(s.id(), s.status().name(), existing.deliveredAt(), existing.distanceMeters(),
                        sha, true));
            }
            throw ApiException.conflict("already-delivered", "a different proof was already accepted");
        }
        if (s.status() != ShipmentStatus.OUT_FOR_DELIVERY) {
            throw ApiException.conflict("not-out-for-delivery", "shipment is " + s.status());
        }
        double meters = pods.distanceMeters(s.id(), at);
        double radius = props.delivery().podRadiusMeters();
        if (meters > radius) {
            return new Refused(new ApiException(422, "too-far-from-address",
                    "proof must be taken within " + Math.round(radius) + " m of the address",
                    Map.of("distanceMeters", Math.round(meters))));
        }
        if (!shipmentService.checkOtp(s, in.otp())) {
            return new Refused(new ApiException(422, "wrong-otp", "the delivery code is wrong or no longer valid"));
        }
        var now = clock.instant();
        pods.insert(new PodStore.Pod(s.id(), device.deviceId(), in.photoType(), sha, meters, now), photo, at);
        var moved = lifecycle.move(s, ShipmentStatus.DELIVERED, "pod", "device:" + device.deviceId());
        shipments.setOtp(s.id(), null);
        return new Done(new PodView(s.id(), moved.status().name(), now, meters, sha, false));
    }

    private byte[] photo(PodIn in) {
        if (in.photoBase64() == null || in.photoType() == null) {
            throw ApiException.invalid("photo-required", "photoBase64 and photoType are required");
        }
        byte[] b;
        try {
            b = Base64.getDecoder().decode(in.photoBase64());
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("bad-photo", "photoBase64 is not base64");
        }
        if (b.length == 0 || b.length > props.delivery().maxPhotoBytes()) {
            throw new ApiException(413, "photo-too-large", "photo must be 1.." + props.delivery().maxPhotoBytes() + " bytes");
        }
        boolean ok = switch (in.photoType()) {
            case "image/jpeg" -> b.length > 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff;
            case "image/png" -> b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
            default -> false;
        };
        if (!ok) {
            throw ApiException.invalid("bad-photo", "photo must be a JPEG or PNG matching photoType");
        }
        return b;
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
