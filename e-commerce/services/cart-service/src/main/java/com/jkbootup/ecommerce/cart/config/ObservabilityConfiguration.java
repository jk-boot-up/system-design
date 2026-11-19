package com.jkbootup.ecommerce.cart.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

/**
 * Keeps traces to what a person would look for: no spans for probes and metric scrapes (/actuator), and none for the
 * outbox relay's polling, which runs ten times a second whether or not there is anything to send. Events the relay does
 * publish still get a producer span, inside the trace of the request that wrote them.
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfiguration {

    @Bean
    ObservationPredicate ignoreProbesAndPolling() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext request) {
                return !request.getCarrier().getRequestURI().startsWith("/actuator");
            }
            if (context instanceof ScheduledTaskObservationContext task) {
                return !task.getTargetClass().getSimpleName().equals("OutboxRelay");
            }
            return true;
        };
    }
}
