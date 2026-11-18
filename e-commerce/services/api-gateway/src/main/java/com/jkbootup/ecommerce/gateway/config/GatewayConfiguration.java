package com.jkbootup.ecommerce.gateway.config;

import com.jkbootup.ecommerce.gateway.adapter.admission.AdmissionControlGatewayFilterFactory;
import com.jkbootup.ecommerce.gateway.adapter.admission.RedisTokenBuckets;
import com.jkbootup.ecommerce.gateway.adapter.filter.RequestIdFilter;
import com.jkbootup.ecommerce.gateway.domain.LocalTokenBuckets;
import com.jkbootup.ecommerce.gateway.domain.RatePolicy;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class GatewayConfiguration {

    public record Limit(double rate, int burst) {}

    @Bean
    Map<String, RatePolicy> ratePolicies(Environment env) {
        Map<String, Limit> limits = Binder.get(env).bind("gateway.rate-limits", Bindable.mapOf(String.class, Limit.class))
                .orElseThrow(() -> new IllegalStateException("gateway.rate-limits is not configured"));
        Map<String, RatePolicy> policies = new LinkedHashMap<>();
        limits.forEach((name, l) -> policies.put(name, new RatePolicy(name, l.rate(), l.burst())));
        return policies;
    }

    @Bean
    RedisTokenBuckets redisTokenBuckets(ReactiveStringRedisTemplate redis, MeterRegistry meters,
                                        @Value("${gateway.rate-limit-store.timeout:50ms}") Duration timeout,
                                        @Value("${gateway.rate-limit-store.degraded-factor:0.25}") double degradedFactor) {
        return new RedisTokenBuckets(redis, new LocalTokenBuckets(100_000, System::nanoTime), degradedFactor, timeout, meters);
    }

    @Bean
    AdmissionControlGatewayFilterFactory admissionControlGatewayFilterFactory(RedisTokenBuckets buckets, Map<String, RatePolicy> ratePolicies) {
        return new AdmissionControlGatewayFilterFactory(buckets, ratePolicies);
    }

    /**
     * A service that is replaced gets a new address. Netty would otherwise trust the DNS answer for its full TTL (600 s
     * from Docker's resolver) and keep idle connections to the old instance, so every request would fail until then.
     */
    @Bean
    HttpClientCustomizer freshUpstreamAddresses() {
        return client -> client.resolver(spec -> spec.cacheMaxTimeToLive(Duration.ofSeconds(10)).cacheNegativeTimeToLive(Duration.ofSeconds(1)));
    }

    @Bean
    RequestIdFilter requestIdFilter() {
        return new RequestIdFilter();
    }

    /**
     * Signature, expiry, issuer and audience are checked; keys are fetched on first use, not at start-up. When the issuer's
     * public URL is not reachable from inside the network (local compose), the keys come from {@code gateway.jwt.jwk-set-uri}
     * while the issuer claim is still checked against the public URL.
     */
    @Bean
    ReactiveJwtDecoder jwtDecoder(@Value("${gateway.jwt.issuer}") String issuer, @Value("${gateway.jwt.audience}") String audience,
                                  @Value("${gateway.jwt.jwk-set-uri:}") String jwkSetUri) {
        NimbusReactiveJwtDecoder decoder = jwkSetUri.isBlank()
                ? NimbusReactiveJwtDecoder.withIssuerLocation(issuer).build()
                : NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> audienceCheck = new JwtClaimValidator<Collection<String>>("aud", aud -> aud != null && aud.contains(audience));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceCheck));
        return decoder;
    }

    /**
     * Coarse access rules at the edge; each service checks again (defence in depth). /internal is never reachable from
     * outside, whatever the routes say.
     */
    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http) {
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(a -> a
                        .pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .pathMatchers("/internal/**").denyAll()
                        .pathMatchers("/fallback/**").permitAll()
                        .pathMatchers("/api/v1/admin/**").hasRole("staff")
                        .pathMatchers(HttpMethod.POST, "/api/v1/products/*/reviews").authenticated()
                        .pathMatchers("/api/v1/orders", "/api/v1/orders/**", "/api/v1/cart/merge").authenticated()
                        .pathMatchers(HttpMethod.GET, "/api/v1/products/**", "/api/v1/categories", "/api/v1/search").permitAll()
                        .pathMatchers("/api/v1/cart", "/api/v1/cart/**").permitAll()                 // guests use X-Guest-Id
                        .anyExchange().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(converter))))
                .build();
    }
}
