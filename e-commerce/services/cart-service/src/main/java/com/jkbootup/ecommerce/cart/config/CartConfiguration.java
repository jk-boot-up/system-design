package com.jkbootup.ecommerce.cart.config;

import io.micrometer.observation.ObservationRegistry;
import com.jkbootup.ecommerce.cart.adapter.client.CatalogPricingClient;
import com.jkbootup.ecommerce.cart.adapter.client.ServiceTokens;
import com.jkbootup.ecommerce.cart.adapter.messaging.OrderConfirmedListener;
import com.jkbootup.ecommerce.cart.adapter.redis.RedisCartRepository;
import com.jkbootup.ecommerce.cart.adapter.redis.RedisProcessedEvents;
import com.jkbootup.ecommerce.cart.application.CartService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import com.jkbootup.ecommerce.cart.adapter.web.ProblemSecurityHandlers;
import jakarta.servlet.DispatcherType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class CartConfiguration {

    @Bean
    CartService cartService(StringRedisTemplate redis,
                            @Value("${cart.ttl:30d}") Duration ttl,
                            @Value("${clients.catalog.url:http://localhost:8081}") String catalogUrl,
                            @Value("${clients.catalog.timeout:300ms}") Duration timeout,
                            @Value("${cart.price-cache:30s}") Duration priceCache,
                            ObservationRegistry observations, ServiceTokens tokens) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build());
        factory.setReadTimeout(timeout);
        RestClient catalog = RestClient.builder().baseUrl(catalogUrl).requestFactory(factory).observationRegistry(observations)
                .requestInterceptor(tokens.interceptor()).build();
        return new CartService(new RedisCartRepository(redis, ttl), new CatalogPricingClient(catalog, priceCache),
                new RedisProcessedEvents(redis));
    }

    /** cart-service's identity towards catalog: client-credentials tokens whose audience names catalog-service. */
    @Bean
    ServiceTokens serviceTokens(ObservationRegistry observations,
                                @Value("${service-auth.token-uri}") String tokenUri,
                                @Value("${service-auth.client-id}") String clientId,
                                @Value("${service-auth.client-secret}") String clientSecret) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
        factory.setReadTimeout(Duration.ofSeconds(2));
        var endpoint = RestClient.builder().baseUrl(tokenUri).requestFactory(factory).observationRegistry(observations).build();
        return new ServiceTokens(endpoint, clientId, clientSecret, Clock.systemUTC(), Duration.ofSeconds(5));
    }

    @Bean
    OrderConfirmedListener orderConfirmedListener(CartService service, JsonMapper json) {
        return new OrderConfirmedListener(service, json);
    }

    /**
     * Guests may use the cart without a token; a token, when sent, must be valid. The checkout snapshot is for
     * order-service only, with its client-credentials token ({@link InternalCallers}).
     */
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        var problems = new ProblemSecurityHandlers();
        return http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()   // an error page must never turn into a 403
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/api/v1/cart/merge").authenticated()
                        .requestMatchers("/internal/carts/**").access(InternalCallers.only("cart-service", "order-service"))
                        .requestMatchers("/api/v1/cart/**", "/api/v1/cart").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> {}).authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .build();
    }
}
