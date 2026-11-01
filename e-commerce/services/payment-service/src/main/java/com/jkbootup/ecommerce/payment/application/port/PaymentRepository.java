package com.jkbootup.ecommerce.payment.application.port;

import com.jkbootup.ecommerce.payment.domain.Payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {

    /** Locks the order's payment row for the rest of the transaction. */
    Optional<Payment> lockByOrder(UUID orderId);

    Optional<Payment> findByOrder(UUID orderId);

    /** @return false when a payment for this order already exists (unique order_id). */
    boolean insert(Payment payment);

    void update(Payment payment);

    /** AUTHORIZING or UNKNOWN payments not touched since {@code before}. */
    List<Payment> findUnsettled(Instant before, int limit);

    void recordAttempt(UUID paymentId, String operation, String outcome, String providerRef, String detail);
}
