package com.jkbootup.ecommerce.payment.application;

import com.jkbootup.ecommerce.payment.application.port.PaymentRepository;
import com.jkbootup.ecommerce.payment.domain.Payment;
import com.jkbootup.ecommerce.payment.domain.PaymentStatus;

import java.time.Clock;
import java.time.Duration;

/**
 * Settles payments whose outcome is unknown, and retries voids that failed. A late provider success for an order that
 * was cancelled (void requested) is voided here, so the customer is never charged for it.
 */
public class PaymentReconciler {

    private final PaymentRepository payments;
    private final PaymentService service;
    private final Clock clock;
    private final Duration settleAfter;

    public PaymentReconciler(PaymentRepository payments, PaymentService service, Clock clock, Duration settleAfter) {
        this.payments = payments;
        this.service = service;
        this.clock = clock;
        this.settleAfter = settleAfter;
    }

    public int reconcileBatch(int limit) {
        var due = payments.findUnsettled(clock.instant().minus(settleAfter), limit);
        for (Payment p : due) {
            try {
                if (p.status().isUnsettled()) service.reconcile(p.orderId());
                else if (p.status() == PaymentStatus.AUTHORIZED && p.voidRequested()) service.voidAuthorization(p.orderId());
            } catch (RuntimeException e) {
                // keep going; this payment is retried on the next pass
            }
        }
        return due.size();
    }
}
