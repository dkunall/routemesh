package com.routemesh.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.routemesh.support.Api;
import com.routemesh.support.ItBase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Planning, insertion and shipment creation under concurrent callers, plus idempotent retries. */
class RoutingConcurrencyIT extends ItBase {

    private static <T> List<T> race(int n, Callable<T> task) throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(n);
        var futures = new ArrayList<Future<T>>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        var out = new ArrayList<T>();
        for (var f : futures) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }

    @Test
    void concurrentPlansNeverAssignAParcelTwice() throws Exception {
        var hub = hub(3, 0, 120);
        var shipper = as(unique("shop"));
        var date = tomorrow();
        var rnd = new java.util.Random(7);
        var ids = new ArrayList<String>();
        for (int i = 0; i < 30; i++) {
            ids.add(shipment(shipper, hub, "DELIVERY", hub.at().off(rnd.nextDouble() * 10 - 5, rnd.nextDouble() * 10 - 5),
                    date).path("id").asText());
        }
        var results = race(4, () -> dispatcher().post("/v1/hubs/" + hub.id() + "/plans",
                Map.of("serviceDate", date.toString())));
        results.forEach(r -> assertThat(r.status()).isIn(200, 409));
        long withRoutes = results.stream().filter(r -> r.status() == 200 && !r.body().path("routes").isEmpty()).count();
        assertThat(withRoutes).isEqualTo(1);
        assertThat(count("select count(*) from route_stop s join route r on r.id = s.route_id where r.hub_id = ?::uuid",
                hub.id())).isEqualTo(30);
        assertThat(count("select count(distinct shipment_id) from route_stop s join route r on r.id = s.route_id "
                + "where r.hub_id = ?::uuid", hub.id())).isEqualTo(30);
        assertThat(count("select count(*) from shipment where hub_id = ?::uuid and status = 'ASSIGNED' and route_id is not null",
                hub.id())).isEqualTo(30);
        // capacity is respected: 30 x 5 kg over 120 kg vans
        assertThat(count("select count(*) from route where hub_id = ?::uuid", hub.id())).isBetween(2L, 3L);

        // a plan is cheaper than the nearest-neighbour baseline on the same input
        JsonNode winner = results.stream().filter(r -> r.status() == 200 && !r.body().path("routes").isEmpty())
                .findFirst().orElseThrow().body();
        assertThat(winner.path("totalKm").asDouble()).isLessThanOrEqualTo(winner.path("baselineKm").asDouble() + 1e-6);

        // a shipper is not a dispatcher
        shipper.post("/v1/hubs/" + hub.id() + "/plans", Map.of("serviceDate", date.toString())).expect(403);
    }

    @Test
    void concurrentInsertsOnTheSameVersionLetExactlyOneThrough() throws Exception {
        var hub = hub(1, 0, 500);
        var shipper = as(unique("shop"));
        var date = tomorrow();
        shipment(shipper, hub, "DELIVERY", hub.at().off(1, 1), date);
        String routeId = plan(hub, date).path("routes").get(0).path("routeId").asText();
        long version = route(routeId).path("version").asLong();
        var late = new ArrayList<String>();
        for (int i = 0; i < 6; i++) {
            late.add(shipment(shipper, hub, "DELIVERY", hub.at().off(-1 - i * 0.5, 2), date).path("id").asText());
        }
        var next = new java.util.concurrent.atomic.AtomicInteger();
        var results = race(6, () -> dispatcher().post("/v1/routes/" + routeId + "/insertions",
                Map.of("shipmentId", late.get(next.getAndIncrement()), "expectedVersion", version)));
        var byStatus = new HashMap<Integer, Integer>();
        results.forEach(r -> byStatus.merge(r.status(), 1, Integer::sum));
        assertThat(byStatus).containsEntry(200, 1).containsEntry(409, 5);
        results.stream().filter(r -> r.status() == 409)
                .forEach(r -> assertThat(r.text("code")).isEqualTo("version-conflict"));
        JsonNode after = route(routeId);
        assertThat(after.path("stops")).hasSize(2);
        assertThat(after.path("version").asLong()).isEqualTo(version + 1);
        assertThat(late.stream().filter(id -> status(id).equals("ASSIGNED")).count()).isEqualTo(1);

        // a stale version on dispatch is refused too
        var d = hub(0, 1, 100).drivers().get(0);
        assertThat(dispatcher().post("/v1/routes/" + routeId + "/dispatch",
                Map.of("driverId", d.id(), "expectedVersion", version)).status()).isIn(409, 422);
    }

    @Test
    void autoDispatchOpensARouteOnAnIdleVanWhenNoPlannedRouteIsLeft() {
        var hub = hub(2, 1, 500);
        var shipper = as(unique("shop"));
        var date = tomorrow();
        shipment(shipper, hub, "DELIVERY", hub.at().off(2, 0), date);
        JsonNode plan = plan(hub, date);
        assertThat(plan.path("routes")).hasSize(1);
        String first = plan.path("routes").get(0).path("routeId").asText();
        dispatch(first, hub.drivers().get(0));
        // the only route is on the road and a delivery cannot join it, so the second van gets a route of its own
        String extra = shipment(shipper, hub, "DELIVERY", hub.at().off(-2, 1), date).path("id").asText();
        JsonNode r = dispatcher().post("/v1/dispatch:auto", Map.of("shipmentId", extra)).expect(200).body();
        assertThat(r.path("routeId").asText()).isNotEqualTo(first);
        assertThat(r.path("onRoad").asBoolean()).isFalse();
        assertThat(r.path("route").path("status").asText()).isEqualTo("PLANNED");
        assertThat(r.path("route").path("stops")).hasSize(1);
        assertThat(status(extra)).isEqualTo("ASSIGNED");
        // the next parcel joins that new planned route instead of looking for a third van
        String third = shipment(shipper, hub, "DELIVERY", hub.at().off(0, 9), date).path("id").asText();
        JsonNode next = dispatcher().post("/v1/dispatch:auto", Map.of("shipmentId", third)).expect(200).body();
        assertThat(next.path("routeId").asText()).isEqualTo(r.path("routeId").asText());
        assertThat(count("select count(*) from route where hub_id = ?::uuid", hub.id())).isEqualTo(2);
    }

    @Test
    void autoDispatchPicksTheCheapestFeasibleRoute() {
        var hub = hub(2, 0, 500);
        var shipper = as(unique("shop"));
        var date = tomorrow();
        // two clusters far apart, so the plan uses both vans
        for (int i = 0; i < 4; i++) {
            shipment(shipper, hub, "DELIVERY", hub.at().off(8 + i * 0.2, 0), date);
            shipment(shipper, hub, "DELIVERY", hub.at().off(-8 - i * 0.2, 0), date);
        }
        JsonNode plan = plan(hub, date);
        String extra = shipment(shipper, hub, "DELIVERY", hub.at().off(8.5, 0.3), date).path("id").asText();
        JsonNode r = dispatcher().post("/v1/dispatch:auto", Map.of("shipmentId", extra)).expect(200).body();
        assertThat(r.path("candidatesEvaluated").asInt()).isEqualTo(plan.path("routes").size());
        assertThat(r.path("addedKm").asDouble()).isGreaterThanOrEqualTo(0.0);
        assertThat(status(extra)).isEqualTo("ASSIGNED");
        // the parcel sits next to the east cluster, so it joins that side's route for a small detour
        assertThat(r.path("addedKm").asDouble()).isLessThan(5.0);
        assertThat(r.path("route").path("stops")).hasSizeGreaterThan(1);
        // a second auto-dispatch of the same parcel is a conflict, not a second stop
        assertThat(dispatcher().post("/v1/dispatch:auto", Map.of("shipmentId", extra)).expect(409).text("code"))
                .isEqualTo("not-plannable");
    }

    @Test
    void shipmentCreationIsIdempotentPerKey() throws Exception {
        var hub = hub(1, 0, 500);
        String subject = unique("shop");
        Api shipper = as(subject);
        var body = shipmentBody(hub, "DELIVERY", hub.at().off(1, 1), tomorrow(), "10:00", "12:00", 3,
                unique("recipient"));
        String k = UUID.randomUUID().toString();

        var first = shipper.post("/v1/shipments", body, key(k)).expect(201);
        var again = shipper.post("/v1/shipments", body, key(k)).expect(200);
        assertThat(again.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(again.text("id")).isEqualTo(first.text("id"));

        var changed = new HashMap<>(body);
        changed.put("weightKg", 4);
        assertThat(shipper.post("/v1/shipments", changed, key(k)).expect(422).text("code"))
                .isEqualTo("idempotency-key-reused");
        assertThat(shipper.post("/v1/shipments", body).expect(400).text("code")).isEqualTo("idempotency-key-required");
        // keys are per caller: another shipper with the same key creates their own shipment
        var otherBody = new HashMap<>(body);
        otherBody.put("reference", "RM-" + UUID.randomUUID().toString().substring(0, 8));
        assertThat(as(unique("shop")).post("/v1/shipments", otherBody, key(k)).expect(201).text("id"))
                .isNotEqualTo(first.text("id"));
        // and cannot read the first one
        assertThat(as(unique("shop")).get("/v1/shipments/" + first.text("id")).status()).isIn(403, 404);

        // eight concurrent retries of a fresh request create one shipment
        var fresh = shipmentBody(hub, "DELIVERY", hub.at().off(2, 1), tomorrow(), "10:00", "12:00", 3,
                unique("recipient"));
        String k2 = UUID.randomUUID().toString();
        var results = race(8, () -> shipper.post("/v1/shipments", fresh, key(k2)));
        results.forEach(r -> assertThat(r.status()).isIn(200, 201, 409));
        assertThat(results.stream().filter(r -> r.status() == 201).count()).isEqualTo(1);
        assertThat(count("select count(*) from shipment where reference = ?", fresh.get("reference"))).isEqualTo(1);

        // validation
        var bad = new HashMap<>(body);
        bad.put("windowEnd", "10:10");
        assertThat(shipper.post("/v1/shipments", bad, key(UUID.randomUUID().toString())).status()).isIn(400, 422);
        bad = new HashMap<>(body);
        bad.put("serviceDate", tomorrow().plusDays(30).toString());
        assertThat(shipper.post("/v1/shipments", bad, key(UUID.randomUUID().toString())).status()).isIn(400, 422);
    }
}
