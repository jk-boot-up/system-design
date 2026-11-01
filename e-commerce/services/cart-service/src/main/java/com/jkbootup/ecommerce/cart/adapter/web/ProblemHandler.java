package com.jkbootup.ecommerce.cart.adapter.web;

import com.jkbootup.ecommerce.cart.domain.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ProblemHandler {

    @ExceptionHandler(DomainException.class)
    public ProblemDetail domain(DomainException e) {
        HttpStatus status = switch (e.code()) {
            case "UNAUTHENTICATED" -> HttpStatus.UNAUTHORIZED;
            case "CART_FULL" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        p.setTitle(status.getReasonPhrase());
        p.setProperty("code", e.code());
        return p;
    }
}
