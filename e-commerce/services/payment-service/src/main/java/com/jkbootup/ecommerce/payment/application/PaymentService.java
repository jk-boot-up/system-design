package com.jkbootup.ecommerce.payment.application;

import com.jkbootup.ecommerce.payment.application.port.PaymentEvents;
import com.jkbootup.ecommerce.payment.application.port.PaymentProvider;
import com.jkbootup.ecommerce.payment.application.port.PaymentProvider.Outcome;
import com.jkbootup.ecommerce.payment.application.port.PaymentRepository;
import com.jkbootup.ecommerce.payment.application.port.Transactions;
import com.jkbootup.ecommerce.payment.domain.DomainException;
import com.jkbootup.ecommerce.payment.domain.Money;
import com.jkbootup.ecommerce.payment.domain.Payment;
import com.jkbootup.ecommerce.payment.domain.PaymentStatus;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Payment use cases, all idempotent per order id. The provider is never called inside a database transaction: the
 * intent is committed first (so a crash leaves an AUTHORIZING row for the reconciler), then the provider is called,
 * then the outcome is committed.
 */
public class PaymentService {

    private final PaymentRepository payments;
    private final PaymentProvider provider;
    private final PaymentEvents events;
    private final Transactions tx;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public PaymentService(PaymentRepository payments, PaymentProvider provider, PaymentEvents events, Transactions tx,
                          Clock clock, Supplier<UUID> ids) {
        this.payments = payments;
        this.provider = provider;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
        this.ids = ids;
    }

    public Payment authorize(UUID orderId, Money amount, String token) {
        Payment payment = tx.inTransaction(() -> {
            var existing = payments.lockByOrder(orderId);
            if (existing.isPresent()) return existing.get();
            Payment p = Payment.authorizing(ids.get(), orderId, amount, token, clock.instant());
            if (!payments.insert(p)) return payments.lockByOrder(orderId).orElseThrow();
            return p;
        });
        if (payment.status() != PaymentStatus.AUTHORIZING) return payment;              // repeat call: current state
        if (payment.voidRequested()) return payment;

        Outcome outcome = provider.authorize(key(orderId), payment.amount(), payment.paymentToken());
        return tx.inTransaction(() -> {
            Payment p = payments.lockByOrder(orderId).orElseThrow();
            if (p.status() != PaymentStatus.AUTHORIZING) return p;                      // settled concurrently
            switch (outcome) {
                case Outcome.Approved a -> {
                    p.authorized(a.providerRef(), clock.instant());
                    payments.recordAttempt(p.id(), "AUTHORIZE", "SUCCESS", a.providerRef(), null);
                    if (p.voidRequested()) voidNow(p);                                 // void won the race
                }
                case Outcome.Declined d -> {
                    p.declined(d.reason(), clock.instant());
                    payments.recordAttempt(p.id(), "AUTHORIZE", "DECLINED", null, d.reason());
                    events.published("payment.PaymentFailed", p);
                }
                case Outcome.Unknown u -> {
                    p.unknown(u.reason(), clock.instant());
                    payments.recordAttempt(p.id(), "AUTHORIZE", "TIMEOUT", null, u.reason());
                }
            }
            payments.update(p);
            return p;
        });
    }

    public Payment capture(UUID orderId) {
        Payment p = find(orderId);
        if (p.status() == PaymentStatus.CAPTURED || p.status() == PaymentStatus.REFUNDED) return p;
        if (p.status() != PaymentStatus.AUTHORIZED || p.voidRequested())
            throw new DomainException("NOT_CAPTURABLE", "Payment for order " + orderId + " is " + p.status());
        Outcome outcome = provider.capture(key(orderId), p.providerRef());
        return tx.inTransaction(() -> {
            Payment locked = payments.lockByOrder(orderId).orElseThrow();
            switch (outcome) {
                case Outcome.Approved a -> {
                    locked.captured(clock.instant());
                    payments.recordAttempt(locked.id(), "CAPTURE", "SUCCESS", locked.providerRef(), null);
                    events.published("payment.PaymentCaptured", locked);
                }
                case Outcome.Declined d -> {
                    payments.recordAttempt(locked.id(), "CAPTURE", "DECLINED", null, d.reason());
                    throw new DomainException("CAPTURE_DECLINED", "Capture declined: " + d.reason());
                }
                case Outcome.Unknown u -> {
                    payments.recordAttempt(locked.id(), "CAPTURE", "TIMEOUT", null, u.reason());
                    throw new DomainException("PROVIDER_UNAVAILABLE", "Capture outcome unknown; retry");
                }
            }
            payments.update(locked);
            return locked;
        });
    }

    /** Voids an authorisation, or makes sure one that is still in flight (or not yet started) is voided. */
    public Payment voidAuthorization(UUID orderId) {
        return tx.inTransaction(() -> {
            var existing = payments.lockByOrder(orderId);
            if (existing.isEmpty()) {
                Payment tombstone = Payment.voidedBeforeAuthorization(ids.get(), orderId, clock.instant());
                if (payments.insert(tombstone)) return tombstone;
                return voidExisting(payments.lockByOrder(orderId).orElseThrow());
            }
            return voidExisting(existing.get());
        });
    }

    public Payment refund(UUID orderId) {
        Payment p = find(orderId);
        if (p.status() == PaymentStatus.REFUNDED) return p;
        if (p.status() != PaymentStatus.CAPTURED)
            throw new DomainException("NOT_REFUNDABLE", "Payment for order " + orderId + " is " + p.status());
        Outcome outcome = provider.refund(key(orderId), p.providerRef(), p.amount());
        return tx.inTransaction(() -> {
            Payment locked = payments.lockByOrder(orderId).orElseThrow();
            if (!(outcome instanceof Outcome.Approved)) {
                payments.recordAttempt(locked.id(), "REFUND", "ERROR", null, outcome.toString());
                throw new DomainException("PROVIDER_UNAVAILABLE", "Refund did not complete; retry");
            }
            locked.refunded(clock.instant());
            payments.recordAttempt(locked.id(), "REFUND", "SUCCESS", locked.providerRef(), null);
            events.published("payment.PaymentRefunded", locked);
            payments.update(locked);
            return locked;
        });
    }

    /** Settles one AUTHORIZING/UNKNOWN payment by asking the provider what happened. Used by the reconciler. */
    public Payment reconcile(UUID orderId) {
        var known = provider.lookup(key(orderId));
        return tx.inTransaction(() -> {
            Payment p = payments.lockByOrder(orderId).orElseThrow();
            if (!p.status().isUnsettled()) return p;
            switch (known.orElse(null)) {
                case Outcome.Approved a -> {
                    p.authorized(a.providerRef(), clock.instant());
                    payments.recordAttempt(p.id(), "RECONCILE", "SUCCESS", a.providerRef(), "provider had authorised");
                    if (p.voidRequested()) voidNow(p);
                }
                case Outcome.Unknown u -> {
                    return p;                                                             // provider still unsure; next pass
                }
                case Outcome.Declined d -> declineAfterReconcile(p, d.reason());
                case null -> declineAfterReconcile(p, "NOT_FOUND_AT_PROVIDER");
            }
            payments.update(p);
            return p;
        });
    }

    public Payment get(UUID orderId) {
        return find(orderId);
    }

    private Payment voidExisting(Payment p) {
        switch (p.status()) {
            case AUTHORIZED -> voidNow(p);
            case AUTHORIZING, UNKNOWN -> p.requestVoid(clock.instant());    // settled by the in-flight call or the reconciler
            case CAPTURED -> throw new DomainException("ALREADY_CAPTURED", "Captured payments are refunded, not voided");
            case DECLINED, VOIDED, REFUNDED -> { return p; }
        }
        p.requestVoid(clock.instant());
        payments.update(p);
        return p;
    }

    private void voidNow(Payment p) {
        Outcome outcome = provider.voidAuthorization(key(p.orderId()), p.providerRef());
        p.requestVoid(clock.instant());
        if (outcome instanceof Outcome.Approved) {
            p.voided(clock.instant());
            payments.recordAttempt(p.id(), "VOID", "SUCCESS", p.providerRef(), null);
        } else {
            payments.recordAttempt(p.id(), "VOID", "ERROR", p.providerRef(), outcome.toString());   // retried by the reconciler
        }
    }

    private void declineAfterReconcile(Payment p, String reason) {
        p.declined(reason, clock.instant());
        payments.recordAttempt(p.id(), "RECONCILE", "DECLINED", null, reason);
        events.published("payment.PaymentFailed", p);
    }

    private Payment find(UUID orderId) {
        return payments.findByOrder(orderId)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "No payment for order " + orderId));
    }

    private static String key(UUID orderId) {
        return orderId.toString();
    }
}
