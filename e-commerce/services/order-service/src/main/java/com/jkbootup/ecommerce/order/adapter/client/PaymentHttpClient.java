package com.jkbootup.ecommerce.order.adapter.client;

import com.jkbootup.ecommerce.order.application.port.PaymentPort;
import com.jkbootup.ecommerce.order.domain.Money;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

/**
 * payment-service client. A timeout during authorise means the outcome is UNKNOWN (the provider may have charged), so
 * authorise is not retried here; payment-service itself is idempotent by order id and reconciles unknown outcomes.
 */
public class PaymentHttpClient implements PaymentPort {

    private final RestClient http;
    private final ResilientCall resilient = new ResilientCall("payment");
    private final JsonMapper json;

    public PaymentHttpClient(RestClient http, JsonMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public Result authorize(UUID orderId, Money amount, String paymentToken) {
        var body = Map.of("orderId", orderId, "paymentToken", paymentToken,
                "amount", Map.of("amount", amount.amount().toPlainString(), "currency", amount.currency().getCurrencyCode()));
        return resilient.run(false, () -> call(() -> http.post().uri("/internal/payments/authorize")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity()), this::onFailure);
    }

    @Override
    public Result capture(UUID orderId) {
        return resilient.run(true, () -> call(() -> http.post().uri("/internal/payments/{id}/capture", orderId)
                .retrieve().toBodilessEntity()), this::onFailure);
    }

    @Override
    public Result voidAuthorization(UUID orderId) {
        return resilient.run(true, () -> call(() -> http.post().uri("/internal/payments/{id}/void", orderId)
                .retrieve().toBodilessEntity()), this::onFailure);
    }

    @Override
    public Result refund(UUID orderId) {
        return resilient.run(true, () -> call(() -> http.post().uri("/internal/payments/{id}/refund", orderId)
                .retrieve().toBodilessEntity()), this::onFailure);
    }

    private Result call(Runnable request) {
        try {
            request.run();
            return new Result.Approved();
        } catch (HttpClientErrorException e) {
            String code = json.readTree(e.getResponseBodyAsString()).path("code").asString("HTTP_" + e.getStatusCode().value());
            if (e.getStatusCode() == HttpStatus.PAYMENT_REQUIRED || e.getStatusCode() == HttpStatus.CONFLICT)
                return new Result.Declined(code);
            return new Result.Unavailable(code);
        }
    }

    private Result onFailure(ResilientCall.Failure f) {
        // A timeout (or 504 from payment-service) may hide a successful charge: report UNKNOWN so the saga voids it.
        return f == ResilientCall.Failure.TIMEOUT ? new Result.Unknown(f.name()) : new Result.Unavailable(f.name());
    }
}
