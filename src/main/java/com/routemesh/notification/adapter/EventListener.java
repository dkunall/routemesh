package com.routemesh.notification.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.routemesh.notification.application.Notifier;
import com.routemesh.outbox.application.EventEnvelope;
import com.routemesh.outbox.application.Outbox;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "routemesh.jobs.enabled", havingValue = "true", matchIfMissing = true)
class EventListener {

    private final Notifier notifier;
    private final ObjectMapper json;
    private final MeterRegistry meters;

    EventListener(Notifier notifier, ObjectMapper json, MeterRegistry meters) {
        this.notifier = notifier;
        this.json = json;
        this.meters = meters;
    }

    @KafkaListener(topics = Outbox.SHIPMENT_EVENTS, groupId = "routemesh-notifier")
    void on(String payload) throws java.io.IOException {
        EventEnvelope e = json.readValue(payload, EventEnvelope.class);
        if (notifier.handle(e)) {
            meters.counter("routemesh.notifications.sent", "type", e.type()).increment();
        }
    }
}
