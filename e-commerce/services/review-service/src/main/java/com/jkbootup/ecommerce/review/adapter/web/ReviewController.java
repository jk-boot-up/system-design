package com.jkbootup.ecommerce.review.adapter.web;

import com.jkbootup.ecommerce.review.application.ReviewService;
import com.jkbootup.ecommerce.review.domain.PostOutcome;
import com.jkbootup.ecommerce.review.domain.Review;
import com.jkbootup.ecommerce.review.domain.ReviewPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products/{sku}/reviews")
public class ReviewController {

    public record PostRequest(@Min(1) @Max(5) int rating, @NotBlank @Size(max = 120) String title, @Size(max = 4000) String body) {}

    public record ReviewView(UUID id, String sku, int rating, String title, String body, boolean verified, Instant createdAt) {
        static ReviewView of(Review r) {
            return new ReviewView(r.id(), r.sku(), r.rating(), r.title(), r.body(), r.verified(), r.createdAt());
        }
    }

    public record Summary(long count, BigDecimal average) {}

    public record PageView(Summary summary, List<ReviewView> items, String nextCursor) {}

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @GetMapping
    public PageView list(@PathVariable String sku, @RequestParam(defaultValue = "0") int limit, @RequestParam(required = false) String cursor) {
        ReviewPage page = service.reviews(sku, limit, cursor);
        return new PageView(new Summary(page.summary().count(), page.summary().average()),
                page.items().stream().map(ReviewView::of).toList(), page.nextCursor());
    }

    /** The author is the token's subject; one review per customer and product. */
    @PostMapping
    public ResponseEntity<ReviewView> post(@PathVariable String sku, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PostRequest request) {
        PostOutcome outcome = service.post(sku, UUID.fromString(jwt.getSubject()), request.rating(), request.title(), request.body());
        return ResponseEntity.status(HttpStatus.CREATED).body(ReviewView.of(outcome.review()));
    }
}
