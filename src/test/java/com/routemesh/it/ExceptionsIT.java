package com.routemesh.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.routemesh.support.ItBase;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The unhappy paths: nobody home, parcels going back, returns, customer reschedules, late vans, old links. */
class ExceptionsIT extends ItBase {

    private static final String OPS = "ops@routemesh.example.test";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private String routeStatus(String routeId) {
        return db.sql("select status from route where id = ?::uuid").param(routeId).query(String.class).single();
    }

    private static List<String> results(JsonNode r) {
        var out = new ArrayList<String>();
        r.forEach(o -> out.add(o.path("result").asText() + (o.path("code").isNull() ? "" : ":" + o.path("code").asText())));
        return out;
    }

    @Test
    void threeFailedAttemptsSendTheParcelBackToTheHub() {
        var hub = hub(1, 1, 500);
        var date = tomorrow();
        var drop = hub.at().off(3, 0);
        JsonNode s = shipment(as(unique("shop")), hub, "DELIVERY", drop, date);
        String id = s.path("id").asText();
        String ref = s.path("reference").asText();
        String routeId = plan(hub, date).path("routes").get(0).path("routeId").asText();
        var d = hub.drivers().get(0);
        dispatch(routeId, d);
        events(d, List.of(event(1, id, "PICKED_UP", null), event(2, id, "IN_TRANSIT", null),
                event(3, id, "OUT_FOR_DELIVERY", null)));

        // "customer not home" has to be reported from the door
        assertThat(results(events(d, List.of(event(4, id, "FAILED_ATTEMPT", null),
                event(5, id, "FAILED_ATTEMPT", drop.off(3, 0)),
                event(6, id, "FAILED_ATTEMPT", drop.off(0.05, 0))))))
                .containsExactly("REJECTED:position-required", "REJECTED:not-at-address", "APPLIED");
        assertThat(status(id)).isEqualTo("FAILED_ATTEMPT");
        assertThat(db.sql("select service_date from shipment where id = ?::uuid").param(id).query(LocalDate.class)
                .single()).isEqualTo(date.plusDays(1));
        String email = recipientOf(id).orElseThrow();
        eventually(() -> mailsTo(email).stream().anyMatch(m -> m.path("Subject").asText().startsWith("We missed you")));

        // two more tries the same evening
        assertThat(results(events(d, List.of(event(7, id, "OUT_FOR_DELIVERY", null),
                event(8, id, "FAILED_ATTEMPT", drop), event(9, id, "OUT_FOR_DELIVERY", null),
                event(10, id, "FAILED_ATTEMPT", drop)))))
                .containsOnly("APPLIED");
        assertThat(status(id)).isEqualTo("RTO");
        assertThat(count("select attempts from shipment where id = ?::uuid", id)).isEqualTo(3);
        eventually(() -> mailsTo(OPS).stream().anyMatch(m -> m.path("Subject").asText().equals("[routemesh] RTO " + ref)));
        // an RTO parcel is not planned again
        assertThat(plan(hub, date.plusDays(1)).path("routes")).isEmpty();

        // the van drives back: entering the hub fence hands the parcel in and closes the route
        drive(d, drop, drop, 1, 1, Instant.now().minusSeconds(240));
        drive(d, hub.at().off(0.5, 0), hub.at(), 2, 60);
        eventually(() -> status(id).equals("RETURNED"));
        eventually(() -> routeStatus(routeId).equals("COMPLETED"));
    }

    @Test
    void aReturnIsCollectedAndHandedInAtTheHub() {
        var hub = hub(1, 1, 500);
        var date = tomorrow();
        var at = hub.at().off(2, 2);
        JsonNode s = shipment(as(unique("shop")), hub, "RETURN", at, date);
        String id = s.path("id").asText();
        String token = s.path("trackingToken").asText();
        String routeId = plan(hub, date).path("routes").get(0).path("routeId").asText();
        var d = hub.drivers().get(0);
        dispatch(routeId, d);

        // a return has no doorstep code; POD is for deliveries
        assertThat(d.device().post("/v1/shipments/" + id + "/pod", pod("123456", at, jpeg(3))).expect(422).text("code"))
                .isEqualTo("not-a-delivery");
        assertThat(results(events(d, List.of(event(1, id, "OUT_FOR_DELIVERY", null), event(2, id, "PICKED_UP", at)))))
                .containsExactly("APPLIED", "APPLIED");

        drive(d, at, at, 1, 1, Instant.now().minusSeconds(300));
        drive(d, hub.at().off(1, 1), hub.at(), 2, 120);
        eventually(() -> status(id).equals("DELIVERED"));
        eventually(() -> routeStatus(routeId).equals("COMPLETED"));
        eventually(() -> anon().get("/v1/track/" + token).body().path("status").asText().equals("DELIVERED"));

        // links stop working after the retention period (fast-forwarded here)
        db.sql("update tracking_view set expires_at = now() - interval '1 minute' where tracking_token = ?")
                .param(token).update();
        assertThat(anon().get("/v1/track/" + token).expect(410).text("code")).isEqualTo("expired");
    }

    @Test
    void customersRescheduleFromTheLinkWithinLimits() {
        var hub = hub(1, 0, 500);
        var shipper = as(unique("shop"));
        JsonNode s = shipment(shipper, hub, "DELIVERY", hub.at().off(1, 1), tomorrow());
        String id = s.path("id").asText();
        String token = s.path("trackingToken").asText();
        String path = "/v1/track/" + token + "/reschedule";
        var newDate = tomorrow().plusDays(2);

        JsonNode moved = anon().post(path, Map.of("date", newDate.toString(), "windowStart", "10:00",
                "windowEnd", "12:00")).expect(200).body();
        assertThat(moved.path("serviceDate").asText()).isEqualTo(newDate.toString());
        assertThat(moved.path("id").isNull() || moved.path("id").isMissingNode()).isTrue();
        assertThat(moved.path("trackingToken").isNull() || moved.path("trackingToken").isMissingNode()).isTrue();
        assertThat(db.sql("select service_date from shipment where id = ?::uuid").param(id).query(LocalDate.class)
                .single()).isEqualTo(newDate);

        assertThat(anon().post(path, Map.of("date", LocalDate.now(IST).toString(), "windowStart", "10:00",
                "windowEnd", "12:00")).expect(400).text("code")).isEqualTo("bad-date");
        anon().post(path, Map.of("date", newDate.toString(), "windowStart", "14:00", "windowEnd", "16:00")).expect(200);
        assertThat(anon().post(path, Map.of("date", newDate.toString(), "windowStart", "10:00", "windowEnd", "12:00"))
                .expect(429).text("code")).isEqualTo("rate-limited");

        // once planned, the link can no longer move it, and a code is only resent while out for delivery
        JsonNode other = shipment(shipper, hub, "DELIVERY", hub.at().off(2, 1), tomorrow());
        String t2 = other.path("trackingToken").asText();
        plan(hub, tomorrow());
        assertThat(anon().post("/v1/track/" + t2 + "/reschedule", Map.of("date", newDate.toString(),
                "windowStart", "10:00", "windowEnd", "12:00")).expect(409).text("code")).isEqualTo("not-reschedulable");
        assertThat(anon().post("/v1/track/" + t2 + "/otp:resend", Map.of()).expect(409).text("code"))
                .isEqualTo("not-out-for-delivery");
        anon().post("/v1/track/" + "x".repeat(101) + "/otp:resend", Map.of()).expect(429);
    }

    @Test
    void aVanThatCannotMakeTheWindowRaisesOneSlaAlert() {
        var nowIst = LocalTime.now(IST);
        assumeThat(nowIst.getHour()).as("needs a same-day window").isBetween(1, 21);
        var hub = hub(1, 1, 500, "00:00", "23:59");
        var today = LocalDate.now(IST);
        var from = nowIst.plusMinutes(40).truncatedTo(ChronoUnit.MINUTES);
        var to = from.plusMinutes(40);
        var drop = hub.at().off(1, 1);
        var body = shipmentBody(hub, "DELIVERY", drop, today, from.toString(), to.toString(), 5, unique("recipient"));
        JsonNode s = as(unique("shop")).post("/v1/shipments", body, key(java.util.UUID.randomUUID().toString()))
                .expect(201).body();
        String id = s.path("id").asText();
        String ref = s.path("reference").asText();
        JsonNode plan = plan(hub, today);
        assertThat(plan.path("routes")).hasSize(1);
        String routeId = plan.path("routes").get(0).path("routeId").asText();
        var d = hub.drivers().get(0);
        dispatch(routeId, d);
        events(d, List.of(event(1, id, "PICKED_UP", null), event(2, id, "IN_TRANSIT", null),
                event(3, id, "OUT_FOR_DELIVERY", null)));

        // the van is 40 km away: roughly 2.5 h at the planning speed, well past the window
        var far = drop.off(40, 0);
        drive(d, far.off(-0.2, 0), far, 3, 10, Instant.now().minusSeconds(30));
        eventually(() -> count("select count(*) from alert where kind = 'SLA_RISK' and shipment_id = ?::uuid", id) == 1);
        eventually(() -> mailsTo(OPS).stream()
                .anyMatch(m -> m.path("Subject").asText().equals("[routemesh] SLA_RISK " + ref)));
        drive(d, far.off(0.1, 0), far.off(0.3, 0), 3, 10);
        eventually(() -> count("select count(*) from route_stop where shipment_id = ?::uuid and predicted_eta > ?",
                id, java.sql.Timestamp.from(Instant.now().plusSeconds(3600))) == 1);
        assertThat(count("select count(*) from alert where kind = 'SLA_RISK' and shipment_id = ?::uuid", id))
                .isEqualTo(1);
    }
}
