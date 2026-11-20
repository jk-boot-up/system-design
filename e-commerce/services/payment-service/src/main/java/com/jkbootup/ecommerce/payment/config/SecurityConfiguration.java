package com.jkbootup.ecommerce.payment.config;

import com.jkbootup.ecommerce.payment.adapter.web.ProblemSecurityHandlers;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Payments are called by order-service only, with its client-credentials token. Nothing else is served. */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    static final String SELF = "payment-service";

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        var problems = new ProblemSecurityHandlers();
        return http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()   // an error page must never turn into a 403
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/internal/payments/**").access(InternalCallers.only(SELF, "order-service"))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> {}).authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .build();
    }
}
