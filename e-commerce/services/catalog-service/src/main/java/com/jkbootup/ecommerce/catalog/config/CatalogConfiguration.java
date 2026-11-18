package com.jkbootup.ecommerce.catalog.config;

import com.jkbootup.ecommerce.catalog.adapter.messaging.OutboxRelay;
import com.jkbootup.ecommerce.catalog.adapter.persistence.JdbcCategoryRepository;
import com.jkbootup.ecommerce.catalog.adapter.persistence.JdbcProductRepository;
import com.jkbootup.ecommerce.catalog.adapter.persistence.OutboxProductEvents;
import com.jkbootup.ecommerce.catalog.adapter.persistence.SpringTransactions;
import com.jkbootup.ecommerce.catalog.adapter.redis.RedisProductCache;
import com.jkbootup.ecommerce.catalog.adapter.storage.MinioImageStorage;
import com.jkbootup.ecommerce.catalog.adapter.web.ImageLinks;
import com.jkbootup.ecommerce.catalog.application.CatalogService;
import io.minio.MinioClient;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;

/** Wires the hexagon: the application core is plain Java, adapters are Spring beans. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class CatalogConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    MinioImageStorage imageStorage(@Value("${catalog.storage.endpoint}") String endpoint,
                                   @Value("${catalog.storage.access-key}") String accessKey,
                                   @Value("${catalog.storage.secret-key}") String secretKey,
                                   @Value("${catalog.storage.bucket}") String bucket,
                                   @Value("${catalog.storage.public-endpoint:}") String publicEndpoint,
                                   @Value("${catalog.storage.region:us-east-1}") String region,
                                   @Value("${catalog.storage.create-bucket:true}") boolean createBucket, Clock clock) {
        var client = MinioClient.builder().endpoint(endpoint).region(region).credentials(accessKey, secretKey).build();
        // The region is set so signing never asks the storage server for it: the public host may be unreachable from here.
        var signer = publicEndpoint.isBlank() ? client
                : MinioClient.builder().endpoint(publicEndpoint).region(region).credentials(accessKey, secretKey).build();
        var storage = new MinioImageStorage(client, signer, bucket, clock);
        if (createBucket) storage.ensureBucket();
        return storage;
    }

    @Bean
    CatalogService catalogService(JdbcClient jdbc, JsonMapper json, TransactionTemplate tx, StringRedisTemplate redis,
                                  MinioImageStorage storage, Clock clock, @Value("${catalog.cache.ttl:10m}") Duration cacheTtl) {
        return new CatalogService(new JdbcProductRepository(jdbc, json), new JdbcCategoryRepository(jdbc),
                new OutboxProductEvents(jdbc, json), new RedisProductCache(redis, json, cacheTtl), storage,
                new SpringTransactions(tx), clock);
    }

    @Bean
    ImageLinks imageLinks(@Value("${catalog.images.public-base-url}") String baseUrl) {
        return new ImageLinks(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
    }

    @Bean
    @ConditionalOnProperty(name = "catalog.jobs.enabled", havingValue = "true", matchIfMissing = true)
    OutboxRelay outboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                            @Value("${catalog.outbox.batch-size:500}") int batchSize) {
        return new OutboxRelay(jdbc, kafka, tx, batchSize);
    }

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource)).usingDbTime().build());
    }

    /**
     * Reads are public, staff endpoints need the {@code staff} role from the token's {@code roles} claim, and /internal
     * relies on the network policy (only cart-service may call it).
     */
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("staff")
                        .requestMatchers("/api/v1/products/**", "/api/v1/categories", "/internal/**").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
                .build();
    }
}
