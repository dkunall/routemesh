package com.routemesh.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.routemesh.support.ItBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Plan, dispatch, pick up, leave the hub, out for delivery, proof of delivery, public page and mail. */
class DeliveryFlowIT extends ItBase {

    @Test
    void parcelGoesFromPlanToProofOfDelivery() {
        var hub = hub(1, 2, 500);
        var shipper = as(unique("shop"));
        var date = tomorrow();
        var drops = List.of(hub.at().off(2, 1), hub.at().off(-1.5, 2.5), hub.at().off(3, -2));
        var ids = new ArrayList<String>();
        var tokens = new ArrayList<String>();
        for (var p : drops) {
            JsonNode s = shipment(shipper, hub, "DELIVERY", p, date);
            assertThat(s.path("status").asText()).isEqualTo("CREATED");
            ids.add(s.path("id").asText());
            tokens.add(s.path("trackingToken").asText());
        }

        JsonNode plan = plan(hub, date);
        assertThat(plan.path("routes")).hasSize(1);
        assertThat(plan.path("unassigned")).isEmpty();
        assertThat(plan.path("totalKm").asDouble()).isPositive()
                .isLessThanOrEqualTo(plan.path("baselineKm").asDouble() + 1e-6);
        String routeId = plan.path("routes").get(0).path("routeId").asText();
        ids.forEach(id -> assertThat(status(id)).isEqualTo("ASSIGNED"));

        var driver = hub.drivers().get(0);
        var other = hub.drivers().get(1);
        assertThat(dispatch(routeId, driver).path("status").asText()).isEqualTo("DISPATCHED");

        // the driver sees today's route; another driver's device cannot read it
        JsonNode route = driver.device().get("/v1/drivers/" + driver.id() + "/route").expect(200).body();
        assertThat(route.path("stops")).hasSize(3);
        other.device().get("/v1/drivers/" + driver.id() + "/route").expect(403);
        anon().get("/v1/drivers/" + driver.id() + "/route").expect(401);
        // a dispatcher JWT is not a device token
        dispatcher().get("/v1/drivers/" + driver.id() + "/route").expect(401);

        var scans = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < ids.size(); i++) {
            scans.add(event(i + 1, ids.get(i), "PICKED_UP", hub.at()));
        }
        JsonNode results = events(driver, scans);
        results.forEach(r -> assertThat(r.path("result").asText()).isEqualTo("APPLIED"));

        // a fix at the hub, then driving out: the hub-exit geofence puts parcels in transit
        drive(driver, hub.at(), hub.at(), 1, 1, java.time.Instant.now().minusSeconds(90));
        eventually(() -> count("select count(*) from geofence_presence p join geofence g on g.id = p.fence_id "
                + "where p.driver_id = ?::uuid and g.kind = 'HUB'", driver.id()) == 1);
        drive(driver, hub.at().off(0.1, 0), hub.at().off(1.2, 0.4), 6, 12);
        eventually(() -> ids.stream().allMatch(id -> status(id).equals("IN_TRANSIT")));

        String first = route.path("stops").get(0).path("shipmentId").asText();
        var firstDrop = drops.get(ids.indexOf(first));
        assertThat(events(driver, List.of(event(4, first, "OUT_FOR_DELIVERY", null))).get(0).path("result").asText())
                .isEqualTo("APPLIED");
        // a driver cannot mark a delivery DELIVERED without proof
        assertThat(events(driver, List.of(event(5, first, "DELIVERED", firstDrop))).get(0).path("code").asText())
                .isEqualTo("pod-required");

        String email = recipientOf(first).orElseThrow();
        String otp = awaitOtp(email, 1);
        String wrong = otp.equals("111111") ? "222222" : "111111";
        String pod = "/v1/shipments/" + first + "/pod";

        var tooFar = driver.device().post(pod, pod(otp, firstDrop.off(2, 0), jpeg(1))).expect(422);
        assertThat(tooFar.text("code")).isEqualTo("too-far-from-address");
        assertThat(tooFar.body().path("distanceMeters").asLong()).isBetween(1800L, 2200L);

        assertThat(driver.device().post(pod, pod(wrong, firstDrop, jpeg(1))).expect(422).text("code"))
                .isEqualTo("wrong-otp");
        assertThat(count("select otp_failures from shipment where id = ?::uuid", first)).isEqualTo(1);
        other.device().post(pod, pod(otp, firstDrop, jpeg(1))).expect(404);

        var ok = driver.device().post(pod, pod(otp, firstDrop.off(0.03, 0), jpeg(1))).expect(200).body();
        assertThat(ok.path("status").asText()).isEqualTo("DELIVERED");
        assertThat(ok.path("replayed").asBoolean()).isFalse();
        assertThat(ok.path("distanceMeters").asDouble()).isBetween(20.0, 40.0);
        assertThat(driver.device().post(pod, pod(otp, firstDrop, jpeg(1))).expect(200).body().path("replayed")
                .asBoolean()).isTrue();
        assertThat(driver.device().post(pod, pod(otp, firstDrop, jpeg(2))).expect(409).text("code"))
                .isEqualTo("already-delivered");
        assertThat(count("select count(*) from pod where shipment_id = ?::uuid", first)).isEqualTo(1);
        assertThat(count("select count(*) from shipment where id = ?::uuid and otp_hash is null", first)).isEqualTo(1);

        String token = tokens.get(ids.indexOf(first));
        eventually(() -> anon().get("/v1/track/" + token).body().path("status").asText().equals("DELIVERED"));
        JsonNode page = anon().get("/v1/track/" + token).expect(200).body();
        assertThat(page.path("deliveredAt").isNull()).isFalse();
        assertThat(page.path("driverArea").isNull() || page.path("driverArea").isMissingNode()).isTrue();
        assertThat(page.toString()).doesNotContain(first).doesNotContain(email);
        anon().get("/v1/track/not-a-token").expect(404);

        eventually(() -> mailsTo(email).stream().anyMatch(m -> m.path("Subject").asText().startsWith("Delivered")));

        // the other two are still out there and have a predicted ETA from the last fix
        String second = route.path("stops").get(1).path("shipmentId").asText();
        eventually(() -> count("select count(*) from route_stop where shipment_id = ?::uuid and predicted_eta is not null",
                second) == 1);
    }

    @Test
    void driverAreaIsShownOnlyWhileOutForDeliveryAndCoarse() {
        var hub = hub(1, 1, 500);
        var shipper = as(unique("shop"));
        var date = tomorrow();
        var drop = hub.at().off(2, 2);
        JsonNode s = shipment(shipper, hub, "DELIVERY", drop, date);
        String id = s.path("id").asText();
        String token = s.path("trackingToken").asText();
        String routeId = plan(hub, date).path("routes").get(0).path("routeId").asText();
        var d = hub.drivers().get(0);
        dispatch(routeId, d);
        events(d, List.of(event(1, id, "PICKED_UP", hub.at())));
        var here = hub.at().off(1.0, 1.0);
        drive(d, hub.at().off(0.5, 0.5), here, 4, 10);
        eventually(() -> anon().get("/v1/track/" + token).body().path("status").asText().equals("PICKED_UP"));
        assertThat(anon().get("/v1/track/" + token).body().path("driverArea").isNull()).isTrue();

        events(d, List.of(event(2, id, "IN_TRANSIT", null), event(3, id, "OUT_FOR_DELIVERY", null)));
        eventually(() -> !anon().get("/v1/track/" + token).body().path("driverArea").isNull());
        JsonNode page = anon().get("/v1/track/" + token).expect(200).body();
        assertThat(page.path("driverName").asText()).isNotBlank();
        double dLat = (page.path("driverArea").path("lat").asDouble() - here.lat()) * 110.574;
        double dLng = (page.path("driverArea").path("lng").asDouble() - here.lng()) * 111.32
                * Math.cos(Math.toRadians(here.lat()));
        double km = Math.hypot(dLat, dLng);
        // an H3 res-8 cell centre: near, but not the exact fix
        assertThat(km).isGreaterThan(0.0).isLessThan(1.0);
        assertThat(page.path("eta").isNull()).isFalse();
    }
}
