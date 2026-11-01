package com.jkbootup.ecommerce.payment.adapter.web;

import com.jkbootup.ecommerce.payment.application.PaymentService;
import com.jkbootup.ecommerce.payment.domain.Money;
import com.jkbootup.ecommerce.payment.domain.Payment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Internal API for order-service's saga. Status codes encode the outcome: 200 ok, 402 declined, 504 unknown. */
@RestController
@RequestMapping("/internal/payments")
public class PaymentController {

    public record MoneyDto(@NotBlank @Pattern(regexp = "\\d{1,15}(\\.\\d{1,2})?") String amount, @NotBlank @Size(min = 3, max = 3) String currency) {}

    public record AuthorizeRequest(@NotNull UUID orderId, @NotNull @Valid MoneyDto amount, @NotBlank @Size(max = 200) String paymentToken) {}

    public record PaymentView(UUID paymentId, UUID orderId, String status, String providerRef, String reason, boolean voidRequested) {
        static PaymentView of(Payment p) {
            return new PaymentView(p.id(), p.orderId(), p.status().name(), p.providerRef(), p.failureReason(), p.voidRequested());
        }
    }

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping("/authorize")
    public ResponseEntity<?> authorize(@Valid @RequestBody AuthorizeRequest r) {
        Payment p = service.authorize(r.orderId(), Money.of(r.amount().amount(), r.amount().currency()), r.paymentToken());
        return switch (p.status()) {
            case AUTHORIZED, CAPTURED, REFUNDED -> ResponseEntity.ok(PaymentView.of(p));
            case DECLINED -> problem(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_DECLINED", "Declined: " + p.failureReason(), p);
            case VOIDED -> problem(HttpStatus.CONFLICT, "PAYMENT_VOIDED", "The payment for this order was voided", p);
            case AUTHORIZING, UNKNOWN -> problem(HttpStatus.GATEWAY_TIMEOUT, "PAYMENT_UNKNOWN",
                    "The provider did not answer in time; the outcome will be reconciled", p);
        };
    }

    @PostMapping("/{orderId}/capture")
    public PaymentView capture(@PathVariable UUID orderId) {
        return PaymentView.of(service.capture(orderId));
    }

    @PostMapping("/{orderId}/void")
    public PaymentView voidPayment(@PathVariable UUID orderId) {
        return PaymentView.of(service.voidAuthorization(orderId));
    }

    @PostMapping("/{orderId}/refund")
    public PaymentView refund(@PathVariable UUID orderId) {
        return PaymentView.of(service.refund(orderId));
    }

    @GetMapping("/{orderId}")
    public PaymentView get(@PathVariable UUID orderId) {
        return PaymentView.of(service.get(orderId));
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail, Payment p) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("code", code);
        pd.setProperty("payment", PaymentView.of(p));
        return ResponseEntity.status(status).body(pd);
    }
}
