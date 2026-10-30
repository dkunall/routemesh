package com.routemesh.tracking.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.outbox.application.EventEnvelope;
import com.routemesh.outbox.application.Outbox;
import com.routemesh.tracking.application.PingProcessor;
import com.routemesh.tracking.application.PingPublisher;
import com.routemesh.tracking.application.TrackingProjector;
import com.routemesh.tracking.domain.Ping;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Kafka edges of the tracking module: ping batches in and out, and the shipment-event projection. */
class KafkaTracking {

    private KafkaTracking() {
    }

    /** Wire format of a ping batch: compact, no domain types. */
    record Fix(double lat, double lng, Instant at) {
    }

    @Component
    static class Publisher implements PingPublisher {

        private final KafkaTemplate<String, String> kafka;
        private final ObjectMapper json;

        Publisher(KafkaTemplate<String, String> kafka, ObjectMapper json) {
            this.kafka = kafka;
            this.json = json;
        }

        @Override
        public void publish(UUID driverId, List<Ping> pings) {
            try {
                var body = json.writeValueAsString(pings.stream()
                        .map(p -> new Fix(p.position().lat(), p.position().lng(), p.at())).toList());
                kafka.send(TOPIC, driverId.toString(), body).get(5, TimeUnit.SECONDS);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while publishing pings", e);
            } catch (ExecutionException | TimeoutException e) {
                throw new IllegalStateException("broker did not acknowledge the ping batch", e);
            }
        }
    }

    @Component
    @ConditionalOnProperty(name = "routemesh.jobs.enabled", havingValue = "true", matchIfMissing = true)
    static class Listeners {

        private final PingProcessor pings;
        private final TrackingProjector projector;
        private final ObjectMapper json;

        Listeners(PingProcessor pings, TrackingProjector projector, ObjectMapper json) {
            this.pings = pings;
            this.projector = projector;
            this.json = json;
        }

        @KafkaListener(topics = PingPublisher.TOPIC, groupId = "routemesh-pings", concurrency = "3")
        void pings(ConsumerRecord<String, String> rec) throws IOException {
            var driver = UUID.fromString(rec.key());
            List<Fix> fixes = json.readValue(rec.value(), new TypeReference<>() {
            });
            pings.process(driver, fixes.stream()
                    .map(f -> new Ping(rec.key(), f.at(), new GeoPoint(f.lat(), f.lng()))).toList());
        }

        @KafkaListener(topics = Outbox.SHIPMENT_EVENTS, groupId = "routemesh-tracking")
        void shipmentEvents(String payload) throws IOException {
            projector.handle(json.readValue(payload, EventEnvelope.class));
        }
    }
}
