package com.jkbootup.ecommerce.review.adapter.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 401 and 403 from Spring Security as problem details with the catalogue's codes, like every other error. */
public final class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        write(request, response, 401, "Unauthorized", "UNAUTHENTICATED", "A valid bearer token is required");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e) throws IOException {
        write(request, response, 403, "Forbidden", "FORBIDDEN", "You are not allowed to do this");
    }

    private static void write(HttpServletRequest request, HttpServletResponse response, int status, String title, String code,
                              String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        String body = "{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + detail + "\",\"code\":\"" + code
                + "\",\"traceId\":\"" + TraceIdAdvice.traceId(request) + "\"}";
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
}
