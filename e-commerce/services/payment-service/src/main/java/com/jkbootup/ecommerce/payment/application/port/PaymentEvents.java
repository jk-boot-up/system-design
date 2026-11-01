package com.jkbootup.ecommerce.payment.application.port;

import com.jkbootup.ecommerce.payment.domain.Payment;

/** Records payment events in the outbox, inside the current transaction. */
public interface PaymentEvents {

    void published(String type, Payment payment);
}
