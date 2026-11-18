package com.jkbootup.ecommerce.gateway.adapter.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a route's circuit breaker sends a request when the service is down, too slow, or its breaker is open: a fast
 * 503 with Retry-After instead of a hanging connection. Other routes keep working (load shedding per service).
 */
@RestController
public class FallbackController {

    @RequestMapping("/fallback/{service}")
    public ResponseEntity<ProblemDetail> fallback(@PathVariable String service) {
        boolean search = "search".equals(service);
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                search ? "Search is temporarily unavailable; product pages still work" : service + " is temporarily unavailable");
        p.setTitle("Service Unavailable");
        p.setProperty("code", search ? "SEARCH_UNAVAILABLE" : "DEPENDENCY_UNAVAILABLE");
        p.setProperty("service", service);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5").body(p);
    }
}
