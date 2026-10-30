package com.routemesh.shared.config;

import java.time.Clock;
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
}
