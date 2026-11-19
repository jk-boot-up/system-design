package com.jkbootup.ecommerce.cart.adapter.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Adds {@code traceId} to every problem response, as the API catalogue promises, so a customer can quote it and support
 * can find every hop in the logs. It is the request id the gateway sets (X-Request-Id) until distributed tracing takes
 * over (P5); a request that bypassed the gateway gets a new one.
 */
@RestControllerAdvice
public class TraceIdAdvice implements ResponseBodyAdvice<Object> {

    public static final String HEADER = "X-Request-Id";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    public static String traceId(HttpServletRequest request) {
        String id = request.getHeader(HEADER);
        return id != null && VALID.matcher(id).matches() ? id : UUID.randomUUID().toString();
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ProblemDetail p && request instanceof ServletServerHttpRequest servlet
                && (p.getProperties() == null || !p.getProperties().containsKey("traceId"))) {
            p.setProperty("traceId", traceId(servlet.getServletRequest()));
        }
        return body;
    }
}
