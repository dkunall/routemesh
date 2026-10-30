package com.routemesh.support;

import java.security.SecureRandom;
import java.util.HexFormat;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** One of each dependency per JVM, started on first use. Credentials are random per run and never printed. */
public final class Containers {

    public static final String DB_PASSWORD = hex(18);
    public static final String REDIS_PASSWORD = hex(18);

    private static PostgreSQLContainer<?> postgres;
    private static KafkaContainer kafka;
    private static GenericContainer<?> redis;
    private static GenericContainer<?> mailpit;

    private Containers() {
    }

    public static synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null) {
            var image = DockerImageName.parse("postgis/postgis:16-3.5-alpine").asCompatibleSubstituteFor("postgres");
            var pg = new PostgreSQLContainer<>(image).withDatabaseName("routemesh").withUsername("routemesh")
                    .withPassword(DB_PASSWORD).withCommand("postgres", "-c", "max_connections=200");
            pg.start();
            postgres = pg;
        }
        return postgres;
    }

    public static synchronized KafkaContainer kafka() {
        if (kafka == null) {
            var k = new KafkaContainer("apache/kafka:3.8.0").withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx256m");
            k.start();
            kafka = k;
        }
        return kafka;
    }

    public static synchronized GenericContainer<?> redis() {
        if (redis == null) {
            GenericContainer<?> r = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", REDIS_PASSWORD).waitingFor(Wait.forListeningPort());
            r.start();
            redis = r;
        }
        return redis;
    }

    public static synchronized GenericContainer<?> mailpit() {
        if (mailpit == null) {
            GenericContainer<?> m = new GenericContainer<>("axllent/mailpit:latest").withExposedPorts(1025, 8025)
                    .waitingFor(Wait.forHttp("/livez").forPort(8025));
            m.start();
            mailpit = m;
        }
        return mailpit;
    }

    public static String mailpitApi() {
        return "http://" + mailpit().getHost() + ":" + mailpit().getMappedPort(8025);
    }

    private static String hex(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
