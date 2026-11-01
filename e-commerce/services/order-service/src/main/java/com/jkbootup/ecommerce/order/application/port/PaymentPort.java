package com.jkbootup.ecommerce.order.application.port;

import com.jkbootup.ecommerce.order.domain.Money;

import java.util.UUID;

/** payment-service, seen from the saga. Keyed by order id, so every call is idempotent. */
public interface PaymentPort {

    sealed interface Result {
        record Approved() implements Result {}

        record Declined(String reason) implements Result {}

        /** No answer in time: the outcome is not known. Treat as "maybe charged" and void. */
        record Unknown(String reason) implements Result {}

        /** The call could not be made (circuit open, connection refused): nothing happened. */
        record Unavailable(String reason) implements Result {}
    }

    Result authorize(UUID orderId, Money amount, String paymentToken);

    Result capture(UUID orderId);

    Result voidAuthorization(UUID orderId);

    Result refund(UUID orderId);
}
