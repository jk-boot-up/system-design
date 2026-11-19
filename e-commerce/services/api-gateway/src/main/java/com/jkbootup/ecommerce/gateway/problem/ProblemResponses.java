package com.jkbootup.ecommerce.gateway.problem;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Problem responses the gateway writes itself (401, 403, 429, 503), with the catalogue's codes and the request id as
 * {@code traceId}. A package of its own so the adapters that use it stay independent of each other. Security runs before
 * the request-id filter, so the id is read from the request (NGINX sets one) and echoed in X-Request-Id here as well.
 */
public final class ProblemResponses {

    public static final String HEADER = "X-Request-Id";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private ProblemResponses() {}

    public static String traceId(ServerWebExchange exchange) {
        String id = exchange.getResponse().getHeaders().getFirst(HEADER);
        if (id == null) id = exchange.getRequest().getHeaders().getFirst(HEADER);
        return id != null && VALID.matcher(id).matches() ? id : UUID.randomUUID().toString();
    }

    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String detail) {
        String traceId = traceId(exchange);
        ServerHttpResponse res = exchange.getResponse();
        res.setStatusCode(status);
        res.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        res.getHeaders().set(HEADER, traceId);
        String body = """
                {"type":"about:blank","title":"%s","status":%d,"detail":"%s","code":"%s","traceId":"%s"}"""
                .formatted(status.getReasonPhrase(), status.value(), detail.replace("\"", "'"), code, traceId);
        DataBuffer buffer = res.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return res.writeWith(Mono.just(buffer));
    }

    public static ServerAuthenticationEntryPoint unauthenticated() {
        return (exchange, e) -> {
            exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer");
            return write(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "A valid bearer token is required");
        };
    }

    public static ServerAccessDeniedHandler forbidden() {
        return (exchange, e) -> write(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not allowed to do this");
    }
}
