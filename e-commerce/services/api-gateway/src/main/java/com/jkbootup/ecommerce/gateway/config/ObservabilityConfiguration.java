package com.jkbootup.ecommerce.gateway.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

/** No spans for probes and metric scrapes (/actuator): traces stay what a person would look for. */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfiguration {

    @Bean
    ObservationPredicate ignoreProbes() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext request
                && request.getCarrier().getPath().value().startsWith("/actuator"));
    }
}
