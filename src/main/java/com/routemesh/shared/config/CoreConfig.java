package com.routemesh.shared.config;

import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(RouteMeshProperties.class)
class CoreConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Pings are keyed by driver; three partitions let three consumers work while each driver stays in order. */
    @Bean
    NewTopic pingTopic() {
        return TopicBuilder.name("routemesh.pings").partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic shipmentEventsTopic() {
        return TopicBuilder.name("routemesh.shipment-events").partitions(3).replicas(1).build();
    }
}
