package com.jkbootup.ecommerce.cart.adapter.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This service's identity for calls to other services: an access token from the realm's token endpoint, obtained with
 * the client-credentials grant and cached.
 *
 * <ul>
 *   <li>The token is renewed once two thirds of its lifetime have passed, not at expiry, so a renewal that fails (the
 *       identity provider restarting) leaves time to try again while the current token is still accepted.</li>
 *   <li>One thread renews; the others keep using the current token, or wait for the first one if there is none yet. A
 *       renewal is attempted at most once per {@code retryAfter}, so an outage does not flood the token endpoint.</li>
 *   <li>Without a valid token the call is not sent and fails like an unreachable service, which the caller already
 *       handles (circuit breaker, degraded mode).</li>
 * </ul>
 */
public final class ServiceTokens {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokens.class);
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(10);

    private record Token(String value, Instant renewAt, Instant expiresAt) {}

    private final RestClient tokenEndpoint;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;
    private final Duration retryAfter;
    private final ReentrantLock renewing = new ReentrantLock();
    private volatile Token token;
    private volatile Instant nextAttempt = Instant.MIN;

    public ServiceTokens(RestClient tokenEndpoint, String clientId, String clientSecret, Clock clock, Duration retryAfter) {
        this.tokenEndpoint = tokenEndpoint;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clock = clock;
        this.retryAfter = retryAfter;
    }

    /**
     * Adds {@code Authorization: Bearer <token>} to every request of the client it is installed on. Without a token the
     * request is not sent and fails like an unreachable service ({@code ResourceAccessException}).
     */
    public ClientHttpRequestInterceptor interceptor() {
        return (request, body, execution) -> {
            try {
                request.getHeaders().setBearerAuth(current());
            } catch (IllegalStateException e) {
                throw new IOException(e.getMessage(), e);
            }
            return execution.execute(request, body);
        };
    }

    public String current() {
        Instant now = clock.instant();
        Token t = token;
        if (t != null && now.isBefore(t.renewAt())) return t.value();
        boolean usable = t != null && now.isBefore(t.expiresAt());
        if (usable) {
            if (now.isBefore(nextAttempt) || !renewing.tryLock()) return t.value();  // someone else renews, or wait a little
        } else {
            renewing.lock();
        }
        try {
            Token latest = token;
            now = clock.instant();
            if (latest != null && now.isBefore(latest.renewAt())) return latest.value();
            try {
                token = fetch(now);
                return token.value();
            } catch (RuntimeException e) {
                nextAttempt = now.plus(retryAfter);
                if (latest != null && now.isBefore(latest.expiresAt())) {
                    log.warn("Service token renewal failed, using the current token until {}: {}", latest.expiresAt(), e.getMessage());
                    return latest.value();
                }
                throw new IllegalStateException("No service token: " + e.getMessage(), e);
            }
        } finally {
            renewing.unlock();
        }
    }

    private Token fetch(Instant now) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        JsonNode body = tokenEndpoint.post()
                .headers(h -> h.setBasicAuth(clientId, clientSecret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        if (body == null || !body.hasNonNull("access_token") || !body.hasNonNull("expires_in"))
            throw new IllegalStateException("token response without access_token or expires_in");
        long lifetime = body.get("expires_in").asLong();
        Instant expiresAt = now.plusSeconds(lifetime).minus(EXPIRY_MARGIN);
        return new Token(body.get("access_token").asString(), now.plusSeconds(lifetime * 2 / 3), expiresAt);
    }
}
