package com.jkbootup.ecommerce.cart.adapter.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Framework errors as RFC 9457 problems with the stable codes of the API catalogue: invalid input is a 400
 * VALIDATION_FAILED with field errors, unknown paths a 404 NOT_FOUND, anything unexpected a 500 INTERNAL_ERROR without
 * internals. Domain errors keep their own handler ({@link ProblemHandler}), which is consulted first.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class RequestProblemHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RequestProblemHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (FieldError e : ex.getBindingResult().getFieldErrors()) {
            errors.add(Map.of("field", e.getField(), "message", String.valueOf(e.getDefaultMessage())));
        }
        ex.getBindingResult().getGlobalErrors()
                .forEach(e -> errors.add(Map.of("field", e.getObjectName(), "message", String.valueOf(e.getDefaultMessage()))));
        return validation(errors);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex, HttpHeaders headers,
                                                                            HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(r -> r.getResolvableErrors().forEach(e -> errors.add(Map.of(
                "field", String.valueOf(r.getMethodParameter().getParameterName()), "message", String.valueOf(e.getDefaultMessage())))));
        return validation(errors);
    }

    /** Every other framework exception (unreadable body, missing parameter, wrong method, unknown path, ...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ProblemDetail p = body instanceof ProblemDetail pd ? pd : ProblemDetail.forStatus(statusCode);
        if (p.getProperties() == null || !p.getProperties().containsKey("code")) p.setProperty("code", codeFor(statusCode));
        if (statusCode.value() == 400 && ex.getMessage() != null && p.getDetail() == null) p.setDetail("The request could not be read");
        return ResponseEntity.status(statusCode).headers(headers).body(p);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception e) {
        log.error("Unexpected error", e);
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        p.setTitle(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
        p.setProperty("code", "INTERNAL_ERROR");
        return p;
    }

    private static ResponseEntity<Object> validation(List<Map<String, String>> errors) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request is not valid");
        p.setTitle(HttpStatus.BAD_REQUEST.getReasonPhrase());
        p.setProperty("code", "VALIDATION_FAILED");
        p.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(p);
    }

    static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "VALIDATION_FAILED";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 503 -> "DEPENDENCY_UNAVAILABLE";
            default -> status.is4xxClientError() ? "BAD_REQUEST" : "INTERNAL_ERROR";
        };
    }
}
