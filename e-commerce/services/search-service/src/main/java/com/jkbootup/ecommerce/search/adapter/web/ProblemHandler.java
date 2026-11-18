package com.jkbootup.ecommerce.search.adapter.web;

import com.jkbootup.ecommerce.search.application.port.SearchIndex;
import com.jkbootup.ecommerce.search.domain.DomainException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps errors to RFC 9457 problem details with a stable {@code code}. */
@RestControllerAdvice
public class ProblemHandler {

    @ExceptionHandler(DomainException.class)
    public ProblemDetail domain(DomainException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        p.setTitle("Bad Request");
        p.setProperty("code", e.code());
        return p;
    }

    /** The gateway shows "search unavailable"; product pages keep working because they read the catalogue. */
    @ExceptionHandler(SearchIndex.IndexUnavailableException.class)
    public ResponseEntity<ProblemDetail> unavailable(SearchIndex.IndexUnavailableException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Search is temporarily unavailable");
        p.setTitle("Service Unavailable");
        p.setProperty("code", "SEARCH_UNAVAILABLE");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5").body(p);
    }
}
