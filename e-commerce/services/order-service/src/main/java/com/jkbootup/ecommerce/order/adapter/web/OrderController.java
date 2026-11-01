package com.jkbootup.ecommerce.order.adapter.web;

import com.jkbootup.ecommerce.order.application.CheckoutResult;
import com.jkbootup.ecommerce.order.application.CheckoutSaga;
import com.jkbootup.ecommerce.order.application.OrderQueries;
import com.jkbootup.ecommerce.order.application.PlaceOrderCommand;
import com.jkbootup.ecommerce.order.domain.Order;
import com.jkbootup.ecommerce.order.domain.OrderLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** Public order API (through the gateway). The customer is the JWT subject; staff have the {@code staff} role. */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    public record PlaceOrderRequest(@NotBlank @Size(max = 200) String paymentToken) {}

    public record MoneyView(String amount, String currency) {}

    public record LineView(String sku, String productName, int quantity, MoneyView unitPrice) {}

    public record OrderView(UUID id, String status, String cancelReason, Instant createdAt, List<LineView> lines, MoneyView total) {
        static OrderView of(Order o) {
            return new OrderView(o.id(), o.status().name(), o.cancelReason() == null ? null : o.cancelReason().name(), o.createdAt(),
                    o.lines().stream().map(OrderController::line).toList(), money(o.total()));
        }
    }

    public record Page(List<OrderView> items, String nextCursor) {}

    private final CheckoutSaga saga;
    private final OrderQueries queries;

    public OrderController(CheckoutSaga saga, OrderQueries queries) {
        this.saga = saga;
        this.queries = queries;
    }

    @PostMapping
    public ResponseEntity<?> place(@AuthenticationPrincipal Jwt jwt,
                                   @RequestHeader("Idempotency-Key") @Size(min = 1, max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String key,
                                   @Valid @RequestBody PlaceOrderRequest request) {
        String email = jwt.getClaimAsString("email");
        CheckoutResult r = saga.place(new PlaceOrderCommand(customer(jwt), email == null ? "" : email, key, request.paymentToken()));
        OrderView view = OrderView.of(r.order());
        return switch (r.outcome()) {
            case CONFIRMED -> ResponseEntity.status(r.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                    .location(URI.create("/api/v1/orders/" + view.id())).body(view);
            case IN_PROGRESS -> ResponseEntity.accepted().location(URI.create("/api/v1/orders/" + view.id())).body(view);
            case OUT_OF_STOCK -> problem(HttpStatus.CONFLICT, "OUT_OF_STOCK", "Not enough stock" + suffix(r.detail()), view);
            case PAYMENT_DECLINED -> problem(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_DECLINED", "The payment was declined", view);
            case PAYMENT_UNKNOWN -> problem(HttpStatus.GATEWAY_TIMEOUT, "PAYMENT_UNKNOWN",
                    "The payment could not be confirmed in time; the order was cancelled and you will not be charged", view);
            case DEPENDENCY_UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5")
                    .body(problemBody(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "Checkout is temporarily unavailable", view));
            case FAILED -> problem(HttpStatus.BAD_GATEWAY, "CHECKOUT_FAILED", "Checkout failed" + suffix(r.detail()), view);
        };
    }

    @GetMapping("/{id}")
    public OrderView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return OrderView.of(queries.get(id, customer(jwt), isStaff(jwt)));
    }

    @GetMapping
    public Page history(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "20") int limit,
                        @RequestParam(required = false) String cursor) {
        Instant before = null;
        UUID beforeId = null;
        if (cursor != null) {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
            before = Instant.parse(parts[0]);
            beforeId = UUID.fromString(parts[1]);
        }
        List<Order> orders = queries.history(customer(jwt), before, beforeId, limit);
        String next = orders.size() < Math.min(Math.max(limit, 1), 100) ? null : Base64.getUrlEncoder().withoutPadding()
                .encodeToString((orders.getLast().createdAt() + "|" + orders.getLast().id()).getBytes(StandardCharsets.UTF_8));
        return new Page(orders.stream().map(OrderView::of).toList(), next);
    }

    @PostMapping("/{id}/cancel")
    public OrderView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return OrderView.of(saga.cancelByCustomer(id, customer(jwt), isStaff(jwt)));
    }

    private static UUID customer(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static boolean isStaff(Jwt jwt) {
        Object roles = jwt.getClaims().getOrDefault("roles", List.of());
        return roles instanceof List<?> l && l.contains("staff");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail, OrderView order) {
        return ResponseEntity.status(status).body(problemBody(status, code, detail, order));
    }

    private static ProblemDetail problemBody(HttpStatus status, String code, String detail, OrderView order) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(status.getReasonPhrase());
        p.setProperty("code", code);
        p.setProperty("order", Map.of("id", order.id(), "status", order.status(),
                "cancelReason", order.cancelReason() == null ? "" : order.cancelReason()));
        return p;
    }

    private static String suffix(String detail) {
        return detail == null || detail.isBlank() ? "" : " (" + detail + ")";
    }

    private static LineView line(OrderLine l) {
        return new LineView(l.sku(), l.productName(), l.quantity(), money(l.unitPrice()));
    }

    private static MoneyView money(com.jkbootup.ecommerce.order.domain.Money m) {
        return new MoneyView(m.amount().toPlainString(), m.currency().getCurrencyCode());
    }
}
