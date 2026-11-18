package com.jkbootup.ecommerce.gateway.adapter.admission;

import com.jkbootup.ecommerce.gateway.domain.RatePolicy;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Admission control as a route filter: {@code AdmissionControl=<policy>,<key>} where key is USER (token subject, else
 * client address), IP, or SKU (the {sku} path variable: one bucket per product, for flash sales). Over the limit the
 * request never reaches a service: 429 with Retry-After and a problem body with code RATE_LIMITED.
 */
public class AdmissionControlGatewayFilterFactory extends AbstractGatewayFilterFactory<AdmissionControlGatewayFilterFactory.Config> {

    public enum KeyKind { USER, IP, SKU }

    public static class Config {
        private String policy;
        private KeyKind key = KeyKind.USER;

        public String getPolicy() { return policy; }
        public void setPolicy(String policy) { this.policy = policy; }
        public KeyKind getKey() { return key; }
        public void setKey(KeyKind key) { this.key = key; }
    }

    private final RedisTokenBuckets buckets;
    private final Map<String, RatePolicy> policies;

    public AdmissionControlGatewayFilterFactory(RedisTokenBuckets buckets, Map<String, RatePolicy> policies) {
        super(Config.class);
        this.buckets = buckets;
        this.policies = policies;
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("policy", "key");
    }

    @Override
    public GatewayFilter apply(Config config) {
        RatePolicy policy = Optional.ofNullable(policies.get(config.getPolicy()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown rate-limit policy " + config.getPolicy()));
        return (exchange, chain) -> key(exchange, config.getKey())
                .flatMap(key -> buckets.take(policy, key))
                .flatMap(d -> {
                    ServerHttpResponse res = exchange.getResponse();
                    res.getHeaders().set("X-RateLimit-Remaining-" + policy.name(), Long.toString(d.remaining()));
                    if (d.allowed()) return chain.filter(exchange);
                    return reject(res, d.retryAfterSeconds(), policy.name());
                });
    }

    static Mono<String> key(ServerWebExchange exchange, KeyKind kind) {
        return switch (kind) {
            case SKU -> Mono.justOrEmpty(ServerWebExchangeUtils.getUriTemplateVariables(exchange).get("sku"))
                    .switchIfEmpty(Mono.just("-"));
            case IP -> Mono.just("ip:" + clientAddress(exchange));
            case USER -> exchange.getPrincipal().map(Principal::getName).map(n -> "user:" + n)
                    .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientAddress(exchange)));
        };
    }

    /** NGINX sets X-Real-IP; the gateway is only reachable through it, so the header can be trusted. */
    static String clientAddress(ServerWebExchange exchange) {
        String real = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null ? "unknown" : remote.getAddress().getHostAddress();
    }

    private static Mono<Void> reject(ServerHttpResponse res, long retryAfter, String policy) {
        res.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        res.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        res.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        String body = """
                {"type":"about:blank","title":"Too Many Requests","status":429,"code":"RATE_LIMITED",\
                "detail":"Too many requests (%s); retry after %d s"}""".formatted(policy, retryAfter);
        DataBuffer buffer = res.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return res.writeWith(Mono.just(buffer));
    }
}
