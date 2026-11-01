package com.jkbootup.ecommerce.cart.adapter.web;

import com.jkbootup.ecommerce.cart.application.CartService;
import com.jkbootup.ecommerce.cart.domain.PricedCart;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Internal API for order-service. Answers 503 when prices cannot be confirmed: an order is never placed at a stale price. */
@RestController
@RequestMapping("/internal/carts")
public class InternalCartController {

    private final CartService service;

    public InternalCartController(CartService service) {
        this.service = service;
    }

    @GetMapping("/{customerId}/checkout-snapshot")
    public ResponseEntity<?> snapshot(@PathVariable UUID customerId) {
        PricedCart cart = service.checkoutSnapshot(customerId);
        if (cart.pricing() == PricedCart.Pricing.PENDING) {
            ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Prices cannot be confirmed right now");
            p.setProperty("code", "PRICING_UNAVAILABLE");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5").body(p);
        }
        return ResponseEntity.ok(CartView.of(cart));
    }
}
