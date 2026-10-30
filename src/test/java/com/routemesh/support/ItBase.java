package com.routemesh.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole app on a random port against real PostGIS, Kafka, Redis and Mailpit. One shared Spring context.
 * Every test builds its own hub somewhere random, so plans and drivers never collide across tests.
 */
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ItBase.TestBeans.class)
public abstract class ItBase {

    public static final String DISPATCHER = "dispatch@it.example.test";
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestJwt.decoder();
        }
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcClient db;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        var pg = Containers.postgres();
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", () -> Containers.DB_PASSWORD);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "40");
        r.add("spring.kafka.bootstrap-servers", () -> Containers.kafka().getBootstrapServers());
        r.add("spring.data.redis.host", () -> Containers.redis().getHost());
        r.add("spring.data.redis.port", () -> Containers.redis().getMappedPort(6379));
        r.add("spring.data.redis.password", () -> Containers.REDIS_PASSWORD);
        r.add("spring.mail.host", () -> Containers.mailpit().getHost());
        r.add("spring.mail.port", () -> Containers.mailpit().getMappedPort(1025));
        r.add("routemesh.security.otp-key", ItBase::randomKey);
        r.add("routemesh.security.dispatcher-subjects", () -> DISPATCHER);
        r.add("routemesh.tracking.gap-wait", () -> "PT3S");
        r.add("routemesh.planning.budget", () -> "PT0.3S");
    }

    private static String randomKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    protected Api anon() {
        return Api.at(port);
    }

    protected Api dispatcher() {
        return as(DISPATCHER);
    }

    protected Api as(String subject) {
        return Api.at(port).as(TestJwt.user(subject));
    }

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@it.example.test";
    }

    protected static Map<String, String> key(String k) {
        return Map.of("Idempotency-Key", k);
    }

    // --- world building

    public record Pt(double lat, double lng) {
        /** A point {@code km} east and {@code kmNorth} north of this one. */
        public Pt off(double kmEast, double kmNorth) {
            return new Pt(lat + kmNorth / 110.574, lng + kmEast / (111.32 * Math.cos(Math.toRadians(lat))));
        }
    }

    public record Driver(String id, String deviceId, Api device) {
    }

    public record Hub(String id, Pt at, List<String> vehicles, List<Driver> drivers) {
    }

    /** A hub at a random spot in a 1-degree box round Bengaluru, far enough from others for the tests. */
    protected Hub hub(int vehicles, int drivers, int capacityKg) {
        return hub(vehicles, drivers, capacityKg, "08:00", "20:00");
    }

    protected Hub hub(int vehicles, int drivers, int capacityKg, String shiftStart, String shiftEnd) {
        var rnd = ThreadLocalRandom.current();
        var at = new Pt(12.5 + rnd.nextDouble(), 77.0 + rnd.nextDouble());
        var d = dispatcher();
        String code = "H" + UUID.randomUUID().toString().substring(0, 6);
        String id = d.post("/v1/hubs", Map.of("code", code, "name", "Hub " + code, "lat", at.lat(), "lng", at.lng()))
                .expect(201).text("id");
        var vs = new ArrayList<String>();
        for (int i = 0; i < vehicles; i++) {
            vs.add(d.post("/v1/hubs/" + id + "/vehicles", Map.of("code", code + "-V" + i, "capacityKg", capacityKg,
                    "shiftStart", shiftStart, "shiftEnd", shiftEnd)).expect(201).text("id"));
        }
        var ds = new ArrayList<Driver>();
        for (int i = 0; i < drivers; i++) {
            String driverId = d.post("/v1/hubs/" + id + "/drivers", Map.of("code", code + "-D" + i, "name",
                    "Driver " + (char) ('A' + i) + " Kumar")).expect(201).text("id");
            var issued = d.post("/v1/drivers/" + driverId + "/devices", Map.of()).expect(201);
            ds.add(new Driver(driverId, issued.text("deviceId"),
                    Api.at(port).with("X-Device-Token", issued.text("token"))));
        }
        return new Hub(id, at, vs, ds);
    }

    protected static LocalDate tomorrow() {
        return LocalDate.now(ZONE).plusDays(1);
    }

    protected Map<String, Object> shipmentBody(Hub h, String kind, Pt at, LocalDate date, String from, String to,
                                               int kg, String email) {
        var b = new LinkedHashMap<String, Object>();
        b.put("hubId", h.id());
        b.put("reference", "RM-" + UUID.randomUUID().toString().substring(0, 8));
        b.put("kind", kind);
        b.put("lat", at.lat());
        b.put("lng", at.lng());
        b.put("address", "Synthetic address");
        b.put("recipientName", "Asha Tester");
        b.put("recipientEmail", email);
        b.put("recipientPhone", "+910000000000");
        b.put("serviceDate", date.toString());
        b.put("windowStart", from);
        b.put("windowEnd", to);
        b.put("weightKg", kg);
        return b;
    }

    protected JsonNode shipment(Api shipper, Hub h, String kind, Pt at, LocalDate date) {
        return shipper.post("/v1/shipments", shipmentBody(h, kind, at, date, "09:00", "20:00", 5,
                unique("recipient")), key(UUID.randomUUID().toString())).expect(201).body();
    }

    protected JsonNode plan(Hub h, LocalDate date) {
        return dispatcher().post("/v1/hubs/" + h.id() + "/plans", Map.of("serviceDate", date.toString()))
                .expect(200).body();
    }

    protected JsonNode route(String routeId) {
        return dispatcher().get("/v1/routes/" + routeId).expect(200).body();
    }

    protected JsonNode dispatch(String routeId, Driver d) {
        var r = route(routeId);
        return dispatcher().post("/v1/routes/" + routeId + "/dispatch",
                Map.of("driverId", d.id(), "expectedVersion", r.path("version").asLong())).expect(200).body();
    }

    protected String status(String shipmentId) {
        return db.sql("select status from shipment where id = ?::uuid").param(shipmentId).query(String.class).single();
    }

    protected Map<String, Object> event(long seq, String shipmentId, String status, Pt at) {
        var e = new LinkedHashMap<String, Object>();
        e.put("deviceSeq", seq);
        e.put("shipmentId", shipmentId);
        e.put("status", status);
        if (at != null) {
            e.put("lat", at.lat());
            e.put("lng", at.lng());
        }
        e.put("at", Instant.now().toString());
        return e;
    }

    protected JsonNode events(Driver d, List<Map<String, Object>> events) {
        return d.device().post("/v1/drivers/" + d.id() + "/events:batch", Map.of("events", events)).expect(200)
                .body().path("results");
    }

    /** Pings along a straight line from a to b, {@code n} fixes ending now, one every {@code everySec}. */
    protected void drive(Driver d, Pt a, Pt b, int n, int everySec) {
        drive(d, a, b, n, everySec, Instant.now());
    }

    /** {@code n} fixes from a to b, {@code everySec} apart, the last one at {@code end}. */
    protected void drive(Driver d, Pt a, Pt b, int n, int everySec, Instant end) {
        var pings = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < n; i++) {
            double f = n == 1 ? 1 : (double) i / (n - 1);
            pings.add(Map.of("lat", a.lat() + (b.lat() - a.lat()) * f, "lng", a.lng() + (b.lng() - a.lng()) * f,
                    "at", end.minusSeconds((long) (n - 1 - i) * everySec).toString()));
        }
        d.device().post("/v1/drivers/" + d.id() + "/pings:batch", Map.of("pings", pings)).expect(202);
    }

    // --- photos

    protected static String jpeg(int seed) {
        byte[] b = new byte[256];
        new java.util.Random(seed).nextBytes(b);
        b[0] = (byte) 0xff;
        b[1] = (byte) 0xd8;
        b[2] = (byte) 0xff;
        return Base64.getEncoder().encodeToString(b);
    }

    protected static Map<String, Object> pod(String otp, Pt at, String photo) {
        return Map.of("otp", otp, "lat", at.lat(), "lng", at.lng(), "photoBase64", photo, "photoType", "image/jpeg");
    }

    // --- mail

    protected List<JsonNode> mailsTo(String address) {
        var out = new ArrayList<JsonNode>();
        var list = Api.atUrl(Containers.mailpitApi()).get("/api/v1/messages?limit=1000").expect(200).body();
        for (JsonNode m : list.path("messages")) {
            for (JsonNode to : m.path("To")) {
                if (to.path("Address").asText().equalsIgnoreCase(address)) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    protected String mailText(JsonNode summary) {
        return Api.atUrl(Containers.mailpitApi()).get("/api/v1/message/" + summary.path("ID").asText()).expect(200)
                .text("Text");
    }

    private static final Pattern CODE = Pattern.compile("door: (\\d{6})");

    /** The newest delivery code mailed to this address. */
    protected String awaitOtp(String email, int nthMail) {
        eventually(() -> mailsTo(email).stream().filter(m -> m.path("Subject").asText().startsWith("Your delivery code"))
                .count() >= nthMail);
        return mailsTo(email).stream().filter(m -> m.path("Subject").asText().startsWith("Your delivery code"))
                .map(this::mailText).map(CODE::matcher).filter(java.util.regex.Matcher::find)
                .map(m -> m.group(1)).findFirst().orElseThrow();
    }

    protected Optional<String> recipientOf(String shipmentId) {
        return db.sql("select recipient_email from shipment where id = ?::uuid").param(shipmentId)
                .query(String.class).optional();
    }

    protected static void eventually(BooleanSupplier check) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(check::getAsBoolean);
    }

    protected long count(String sql, Object... params) {
        return db.sql(sql).params(params).query(Long.class).single();
    }

    protected static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }
}
