package com.jkbootup.ecommerce.cart.adapter.web;

import com.jkbootup.ecommerce.cart.application.CartService;
import com.jkbootup.ecommerce.cart.domain.CartOwner;
import com.jkbootup.ecommerce.cart.domain.DomainException;
import com.jkbootup.ecommerce.cart.domain.PricedCart;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Public cart API. A signed-in customer is identified by the JWT; a guest by the opaque X-Guest-Id header. */
@RestController
@RequestMapping("/api/v1/cart")
public class CartController {

    public record QuantityRequest(@Min(0) @Max(99) int quantity) {}

    public record MergeRequest(@NotNull UUID guestId) {}

    private final CartService service;

    public CartController(CartService service) {
        this.service = service;
    }

    @GetMapping
    public CartView get(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = "X-Guest-Id", required = false) UUID guest) {
        return CartView.of(service.view(owner(jwt, guest)));
    }

    @PutMapping("/items/{sku}")
    public CartView setQuantity(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = "X-Guest-Id", required = false) UUID guest,
                                @PathVariable String sku, @Valid @RequestBody QuantityRequest request) {
        return CartView.of(service.setQuantity(owner(jwt, guest), sku, request.quantity()));
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = "X-Guest-Id", required = false) UUID guest) {
        service.clear(owner(jwt, guest));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/merge")
    public CartView merge(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MergeRequest request) {
        if (jwt == null) throw new DomainException("UNAUTHENTICATED", "Sign in to merge a guest cart");
        return CartView.of(service.merge(UUID.fromString(jwt.getSubject()), request.guestId()));
    }

    static CartOwner owner(Jwt jwt, UUID guest) {
        if (jwt != null) return CartOwner.customer(UUID.fromString(jwt.getSubject()));
        if (guest != null) return CartOwner.guest(guest);
        throw new DomainException("OWNER_REQUIRED", "Send a customer token or an X-Guest-Id header");
    }
}
