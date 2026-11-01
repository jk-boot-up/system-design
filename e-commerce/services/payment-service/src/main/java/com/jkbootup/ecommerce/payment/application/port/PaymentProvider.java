package com.jkbootup.ecommerce.payment.application.port;

import com.jkbootup.ecommerce.payment.domain.Money;

import java.util.Optional;

/**
 * The external payment provider (Strategy). Every call carries an idempotency key (the order id), so a repeated call is
 * never charged twice. Implementations translate the provider's own model (anti-corruption layer).
 */
public interface PaymentProvider {

    sealed interface Outcome {
        record Approved(String providerRef) implements Outcome {}

        record Declined(String reason) implements Outcome {}

        /** No answer in time, or a transport error: the provider may or may not have acted. */
        record Unknown(String reason) implements Outcome {}
    }

    Outcome authorize(String idempotencyKey, Money amount, String paymentToken);

    Outcome capture(String idempotencyKey, String providerRef);

    Outcome voidAuthorization(String idempotencyKey, String providerRef);

    Outcome refund(String idempotencyKey, String providerRef, Money amount);

    /** What the provider knows about an authorisation with this key; empty when it never saw one. */
    Optional<Outcome> lookup(String idempotencyKey);
}
