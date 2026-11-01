package com.jkbootup.ecommerce.cart.config;

import com.jkbootup.ecommerce.cart.adapter.client.CatalogPricingClient;
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
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class CartConfiguration {

    @Bean
    CartService cartService(StringRedisTemplate redis,
                            @Value("${cart.ttl:30d}") Duration ttl,
                            @Value("${clients.catalog.url:http://localhost:8081}") String catalogUrl,
                            @Value("${clients.catalog.timeout:300ms}") Duration timeout,
                            @Value("${cart.price-cache:30s}") Duration priceCache) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build());
        factory.setReadTimeout(timeout);
        RestClient catalog = RestClient.builder().baseUrl(catalogUrl).requestFactory(factory).build();
        return new CartService(new RedisCartRepository(redis, ttl), new CatalogPricingClient(catalog, priceCache),
                new RedisProcessedEvents(redis));
    }

    @Bean
    OrderConfirmedListener orderConfirmedListener(CartService service, JsonMapper json) {
        return new OrderConfirmedListener(service, json);
    }

    /** Guests may use the cart without a token; a token, when sent, must be valid. Internal endpoints rely on network policy. */
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/api/v1/cart/merge").authenticated()
                        .requestMatchers("/api/v1/cart/**", "/api/v1/cart", "/internal/**").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> {}))
                .build();
    }
}
