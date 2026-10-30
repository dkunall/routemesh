package com.routemesh.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.routemesh.support.ItBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** Offline phones: replays, out-of-order uploads, gaps, and the same batch sent from several retries at once. */
class DeviceSyncIT extends ItBase {

    record Day(Hub hub, Driver driver, List<String> shipments) {
    }

    private Day dispatched(int parcels) {
        var hub = hub(1, 1, 500);
        var shipper = as(unique("shop"));
        var date = tomorrow();
        var ids = new ArrayList<String>();
        for (int i = 0; i < parcels; i++) {
            ids.add(shipment(shipper, hub, "DELIVERY", hub.at().off(1 + i, 1), date).path("id").asText());
        }
        String routeId = plan(hub, date).path("routes").get(0).path("routeId").asText();
        dispatch(routeId, hub.drivers().get(0));
        return new Day(hub, hub.drivers().get(0), ids);
    }

    private static List<String> results(JsonNode r) {
        var out = new ArrayList<String>();
        r.forEach(o -> out.add(o.path("result").asText() + (o.path("code").isNull() ? "" : ":" + o.path("code").asText())));
        return out;
    }

    @Test
    void outOfOrderUploadsApplyInSequenceAndReplaysAreNoOps() {
        var day = dispatched(2);
        var d = day.driver();
        String s1 = day.shipments().get(0);

        // seq 2 and 3 arrive before seq 1: they wait, nothing moves
        assertThat(results(events(d, List.of(event(3, s1, "OUT_FOR_DELIVERY", null), event(2, s1, "IN_TRANSIT", null)))))
                .containsExactly("WAITING_FOR_GAP", "WAITING_FOR_GAP");
        assertThat(status(s1)).isEqualTo("ASSIGNED");

        // seq 1 fills the gap; all three apply in device order
        assertThat(results(events(d, List.of(event(1, s1, "PICKED_UP", null))))).containsExactly("APPLIED");
        assertThat(status(s1)).isEqualTo("OUT_FOR_DELIVERY");
        JsonNode timeline = dispatcher().get("/v1/shipments/" + s1 + "/timeline").expect(200).body();
        var order = new ArrayList<String>();
        timeline.forEach(e -> order.add(e.path("deviceSeq").asLong() + ":" + e.path("status").asText()));
        assertThat(order).containsExactly("1:PICKED_UP", "2:IN_TRANSIT", "3:OUT_FOR_DELIVERY");
        as(unique("shop")).get("/v1/shipments/" + s1 + "/timeline").expect(403);

        // a replay of the same event is a duplicate; a different event under a used number is refused
        assertThat(results(events(d, List.of(event(2, s1, "IN_TRANSIT", null))))).containsExactly("DUPLICATE:applied");
        assertThat(results(events(d, List.of(event(2, s1, "FAILED_ATTEMPT", null)))))
                .containsExactly("REJECTED:sequence-reused");
        assertThat(status(s1)).isEqualTo("OUT_FOR_DELIVERY");
    }

    @Test
    void aMissingNumberIsSkippedAfterTheWaitAndALateEventCannotMoveBackwards() {
        var day = dispatched(2);
        var d = day.driver();
        String s1 = day.shipments().get(0);
        String s2 = day.shipments().get(1);
        events(d, List.of(event(1, s1, "PICKED_UP", null), event(2, s1, "IN_TRANSIT", null),
                event(3, s1, "OUT_FOR_DELIVERY", null)));

        // seq 4 never arrives (phone wiped it); seq 5 waits for gap-wait (3 s in tests), then applies
        assertThat(results(events(d, List.of(event(5, s2, "PICKED_UP", null))))).containsExactly("WAITING_FOR_GAP");
        assertThat(status(s2)).isEqualTo("ASSIGNED");
        eventually(() -> status(s2).equals("PICKED_UP"));

        // seq 4 turns up after all: it is applied on its own, and the state machine refuses to go backwards
        assertThat(results(events(d, List.of(event(4, s1, "IN_TRANSIT", null)))))
                .containsExactly("REJECTED:illegal-transition");
        assertThat(status(s1)).isEqualTo("OUT_FOR_DELIVERY");
        // and the next number applies at once
        assertThat(results(events(d, List.of(event(6, s2, "IN_TRANSIT", null))))).containsExactly("APPLIED");
    }

    @Test
    void eventsAreCheckedAgainstTheDriversOwnRoute() {
        var day = dispatched(1);
        var other = dispatched(1);
        var d = day.driver();
        String mine = day.shipments().get(0);
        String theirs = other.shipments().get(0);
        var unplanned = shipment(as(unique("shop")), day.hub(), "DELIVERY", day.hub().at().off(2, 2), tomorrow())
                .path("id").asText();

        assertThat(results(events(d, List.of(
                event(1, theirs, "PICKED_UP", null),
                event(2, unplanned, "PICKED_UP", null),
                event(3, mine, "CANCELLED", null),
                event(4, mine, "TELEPORTED", null),
                event(5, mine, "PICKED_UP", null)))))
                .containsExactly("REJECTED:not-your-shipment", "REJECTED:not-your-shipment",
                        "REJECTED:not-driver-settable", "REJECTED:unknown-status", "APPLIED");
        assertThat(status(theirs)).isEqualTo("ASSIGNED");
        assertThat(status(unplanned)).isEqualTo("CREATED");

        // a device can only post for its own driver
        d.device().post("/v1/drivers/" + other.driver().id() + "/events:batch",
                Map.of("events", List.of(event(1, theirs, "PICKED_UP", null)))).expect(403);
        // batch limits
        var tooMany = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 201; i++) {
            tooMany.add(event(100 + i, mine, "IN_TRANSIT", null));
        }
        d.device().post("/v1/drivers/" + d.id() + "/events:batch", Map.of("events", tooMany)).expect(400);
    }

    @Test
    void theSameBatchFromEightConcurrentRetriesAppliesOnce() throws Exception {
        var day = dispatched(3);
        var d = day.driver();
        var batch = new ArrayList<Map<String, Object>>();
        long seq = 1;
        for (var s : day.shipments()) {
            batch.add(event(seq++, s, "PICKED_UP", null));
            batch.add(event(seq++, s, "IN_TRANSIT", null));
        }
        int threads = 8;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);
        var tasks = new ArrayList<java.util.concurrent.Future<List<String>>>();
        for (int i = 0; i < threads; i++) {
            Callable<List<String>> c = () -> {
                start.await();
                return results(events(d, batch));
            };
            tasks.add(pool.submit(c));
        }
        start.countDown();
        int applied = 0;
        int duplicate = 0;
        for (var t : tasks) {
            for (var r : t.get()) {
                if (r.startsWith("APPLIED")) {
                    applied++;
                } else if (r.startsWith("DUPLICATE")) {
                    duplicate++;
                }
            }
        }
        pool.shutdown();
        assertThat(applied).isEqualTo(batch.size());
        assertThat(duplicate).isEqualTo(batch.size() * (threads - 1));
        for (var s : day.shipments()) {
            assertThat(status(s)).isEqualTo("IN_TRANSIT");
            assertThat(count("select count(*) from outbox where type = 'shipment.status-changed' "
                    + "and payload->'data'->>'shipmentId' = ? and payload->'data'->>'status' = 'PICKED_UP'", s)).isEqualTo(1);
        }
        assertThat(count("select count(*) from tracking_event where device_id = ?::uuid", d.deviceId()))
                .isEqualTo(batch.size());
    }
}
