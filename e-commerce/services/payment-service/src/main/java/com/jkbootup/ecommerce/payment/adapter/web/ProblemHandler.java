package com.jkbootup.ecommerce.payment.adapter.web;

import com.jkbootup.ecommerce.payment.domain.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ProblemHandler {

    @ExceptionHandler(DomainException.class)
    public ProblemDetail domain(DomainException e) {
        HttpStatus status = switch (e.code()) {
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "INVALID_AMOUNT", "INVALID_TOKEN" -> HttpStatus.BAD_REQUEST;
            case "PROVIDER_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "CAPTURE_DECLINED" -> HttpStatus.PAYMENT_REQUIRED;
            default -> HttpStatus.CONFLICT;
        };
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        p.setTitle(status.getReasonPhrase());
        p.setProperty("code", e.code());
        return p;
    }
}
