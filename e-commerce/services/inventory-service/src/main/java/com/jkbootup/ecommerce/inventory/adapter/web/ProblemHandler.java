package com.jkbootup.ecommerce.inventory.adapter.web;

import com.jkbootup.ecommerce.inventory.domain.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain errors to RFC 9457 problem details with a stable {@code code}. */
@RestControllerAdvice
public class ProblemHandler {

    @ExceptionHandler(DomainException.class)
    public ProblemDetail domain(DomainException e) {
        HttpStatus status = switch (e.code()) {
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "INVALID_SKU", "INVALID_QUANTITY", "EMPTY_ORDER" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.CONFLICT;
        };
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        p.setTitle(status.getReasonPhrase());
        p.setProperty("code", e.code());
        return p;
    }
}
