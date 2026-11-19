package com.jkbootup.ecommerce.gateway.adapter.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import io.micrometer.common.KeyValue;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Every request gets an X-Request-Id: the caller's (NGINX sets one) if it is well formed, otherwise a new one. It is
 * passed to the service and echoed in the response, so a support ticket can be matched with the logs of every hop. It
 * also tags the request's server span ({@code request.id}), so the trace of any problem response (whose {@code traceId}
 * is this id) is found in Tempo with {@code { span.request.id = "<id>" }}.
 */
public class RequestIdFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-Request-Id";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String id = incoming != null && VALID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        ServerHttpRequest request = exchange.getRequest().mutate().headers(h -> h.set(HEADER, id)).build();
        exchange.getResponse().getHeaders().set(HEADER, id);
        ServerRequestObservationContext.findCurrent(exchange.getAttributes())
                .ifPresent(context -> context.addHighCardinalityKeyValue(KeyValue.of("request.id", id)));
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
