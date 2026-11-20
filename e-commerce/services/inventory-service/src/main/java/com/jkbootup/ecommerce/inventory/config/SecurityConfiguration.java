package com.jkbootup.ecommerce.inventory.config;

import com.jkbootup.ecommerce.inventory.adapter.web.ProblemSecurityHandlers;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Reservations are called by order-service only, with its client-credentials token. Stock is set by staff through the
 * gateway ({@code PUT /api/v1/admin/stock/{sku}} is rewritten to {@code /internal/stock/{sku}} and keeps the staff
 * member's token), so it needs the {@code staff} role.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    static final String SELF = "inventory-service";

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        var problems = new ProblemSecurityHandlers();
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()   // an error page must never turn into a 403
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/internal/reservations", "/internal/reservations/**").access(InternalCallers.only(SELF, "order-service"))
                        .requestMatchers(HttpMethod.GET, "/internal/stock/*").hasRole("staff")
                        .requestMatchers(HttpMethod.PUT, "/internal/stock/*").hasRole("staff")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)).authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .build();
    }
}
