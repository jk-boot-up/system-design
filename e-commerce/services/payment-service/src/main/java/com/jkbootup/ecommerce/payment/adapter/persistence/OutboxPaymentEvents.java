package com.jkbootup.ecommerce.payment.adapter.persistence;

import com.jkbootup.ecommerce.payment.application.port.PaymentEvents;
import com.jkbootup.ecommerce.payment.domain.Payment;

import java.util.LinkedHashMap;
import java.util.Map;

/** payment.* events into the outbox (topic payment.events, key = order id). */
public class OutboxPaymentEvents implements PaymentEvents {

    private final OutboxWriter outbox;

    public OutboxPaymentEvents(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    @Override
    public void published(String type, Payment p) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", p.id().toString());
        payload.put("orderId", p.orderId().toString());
        if (p.amount() != null)
            payload.put("amount", Map.of("amount", p.amount().amount().toPlainString(), "currency", p.amount().currency().getCurrencyCode()));
        payload.put("status", p.status().name());
        payload.put("reason", p.failureReason());
        outbox.append("payment.events", "Payment", p.orderId().toString(), type, payload);
    }
}
