package com.jkbootup.ecommerce.inventory.adapter.web;

import com.jkbootup.ecommerce.inventory.application.ReservationService;
import com.jkbootup.ecommerce.inventory.domain.OrderLine;
import com.jkbootup.ecommerce.inventory.domain.Quantity;
import com.jkbootup.ecommerce.inventory.domain.ReserveOutcome;
import com.jkbootup.ecommerce.inventory.domain.Sku;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Internal API used by order-service's checkout saga. Every command is idempotent per order id. */
@RestController
@RequestMapping("/internal/reservations")
public class ReservationController {

    public record LineRequest(@NotBlank String sku, @Min(1) @Max(10_000) int quantity) {}

    public record ReserveRequest(@NotNull UUID orderId, @NotEmpty @Size(max = 200) List<@Valid LineRequest> lines) {}

    public record ReservationResponse(UUID orderId, String status, Instant expiresAt) {}

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> reserve(@Valid @RequestBody ReserveRequest request) {
        List<OrderLine> lines = request.lines().stream()
                .map(l -> new OrderLine(Sku.of(l.sku()), Quantity.of(l.quantity()))).toList();
        return switch (service.reserve(request.orderId(), lines)) {
            case ReserveOutcome.Reserved r ->
                    ResponseEntity.ok(new ReservationResponse(request.orderId(), "RESERVED", r.expiresAt()));
            case ReserveOutcome.OutOfStock o -> {
                ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Not enough stock for " + o.sku());
                p.setTitle("Out of stock");
                p.setProperty("code", "OUT_OF_STOCK");
                p.setProperty("sku", o.sku().value());
                yield ResponseEntity.status(HttpStatus.CONFLICT).body(p);
            }
        };
    }

    @PostMapping("/{orderId}/commit")
    public Map<String, Object> commit(@PathVariable UUID orderId) {
        service.commit(orderId);
        return Map.of("orderId", orderId, "status", "COMMITTED");
    }

    @PostMapping("/{orderId}/release")
    public Map<String, Object> release(@PathVariable UUID orderId) {
        service.release(orderId);
        return Map.of("orderId", orderId, "status", "RELEASED");
    }

    @PostMapping("/{orderId}/return")
    public Map<String, Object> returnStock(@PathVariable UUID orderId) {
        service.returnStock(orderId);
        return Map.of("orderId", orderId, "status", "RETURNED");
    }
}
