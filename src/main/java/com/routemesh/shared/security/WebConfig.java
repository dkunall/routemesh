package com.routemesh.shared.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration(proxyBeanMethods = false)
class WebConfig implements WebMvcConfigurer {

    private final CallerResolver callers;
    private final DeviceResolver devices;

    WebConfig(CallerResolver callers, DeviceResolver devices) {
        this.callers = callers;
        this.devices = devices;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(callers);
        resolvers.add(devices);
    }
}
