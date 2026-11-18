package com.jkbootup.ecommerce.review.config;

import com.datastax.oss.driver.api.core.CqlSession;
import com.jkbootup.ecommerce.review.adapter.cassandra.CassandraReviewStore;
import com.jkbootup.ecommerce.review.adapter.cassandra.SchemaInitializer;
import com.jkbootup.ecommerce.review.adapter.messaging.KafkaReviewEvents;
import com.jkbootup.ecommerce.review.adapter.messaging.OrderConfirmedListener;
import com.jkbootup.ecommerce.review.application.ReviewService;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/** Wires the hexagon: the application core is plain Java, adapters are Spring beans. */
@Configuration(proxyBeanMethods = false)
public class ReviewConfiguration {

    @Bean
    CassandraReviewStore reviewStore(CqlSession session,
                                     @Value("${review.cassandra.replication:{'class': 'SimpleStrategy', 'replication_factor': 1}}") String replication) {
        SchemaInitializer.apply(session, replication);
        return new CassandraReviewStore(session);
    }

    @Bean
    ReviewService reviewService(CassandraReviewStore store, KafkaTemplate<String, String> kafka, JsonMapper json) {
        return new ReviewService(store, store, new KafkaReviewEvents(kafka, json), Clock.systemUTC());
    }

    @Bean
    OrderConfirmedListener orderConfirmedListener(ReviewService service, JsonMapper json) {
        return new OrderConfirmedListener(service, json);
    }

    /** Unreadable events go to {@code <topic>.dlq}; Cassandra outages are retried with back-off without skipping. */
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka) {
        var recoverer = new DeadLetterPublishingRecoverer(kafka, (record, e) -> new TopicPartition(record.topic() + ".dlq", -1));
        var backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxInterval(30_000);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(OrderConfirmedListener.BadEventException.class);
        return handler;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/products/*/reviews").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/products/*/reviews").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> {}))
                .build();
    }
}
