package com.jkbootup.ecommerce.catalog.adapter.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import com.jkbootup.ecommerce.catalog.domain.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain errors to RFC 9457 problem details with a stable {@code code}. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)   // domain errors first; RequestProblemHandler takes the rest
public class ProblemHandler {

    @ExceptionHandler(DomainException.class)
    public ProblemDetail domain(DomainException e) {
        HttpStatus status = switch (e.code()) {
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "VERSION_MISMATCH" -> HttpStatus.PRECONDITION_FAILED;
            case "PRECONDITION_REQUIRED" -> HttpStatus.PRECONDITION_REQUIRED;
            case "IMAGE_MISSING", "UNKNOWN_CATEGORY" -> HttpStatus.UNPROCESSABLE_CONTENT;
            case "SKU_TAKEN", "SLUG_TAKEN", "INVALID_TRANSITION", "TOO_MANY_IMAGES" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        p.setTitle(status.getReasonPhrase());
        p.setProperty("code", e.code());
        return p;
    }
}
