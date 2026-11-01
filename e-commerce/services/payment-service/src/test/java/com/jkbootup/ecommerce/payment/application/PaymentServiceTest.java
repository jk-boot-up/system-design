package com.jkbootup.ecommerce.payment.application;

import com.jkbootup.ecommerce.payment.adapter.provider.SimulatedPaymentProvider;
import com.jkbootup.ecommerce.payment.application.port.PaymentEvents;
import com.jkbootup.ecommerce.payment.application.port.PaymentRepository;
import com.jkbootup.ecommerce.payment.application.port.Transactions;
import com.jkbootup.ecommerce.payment.domain.DomainException;
import com.jkbootup.ecommerce.payment.domain.Money;
import com.jkbootup.ecommerce.payment.domain.Payment;
import com.jkbootup.ecommerce.payment.domain.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uses the real simulated provider (with short delays) and an in-memory repository. */
class PaymentServiceTest {

    private final Map<UUID, Payment> rows = new ConcurrentHashMap<>();
    private final List<String> attempts = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final SimulatedPaymentProvider provider = new SimulatedPaymentProvider(Duration.ofMillis(100), Duration.ofMillis(300));
    private final Clock clock = Clock.systemUTC();

    private final PaymentRepository repo = new PaymentRepository() {
        public Optional<Payment> lockByOrder(UUID o) { return Optional.ofNullable(rows.get(o)); }

        public Optional<Payment> findByOrder(UUID o) { return Optional.ofNullable(rows.get(o)); }

        public synchronized boolean insert(Payment p) { return rows.putIfAbsent(p.orderId(), p) == null; }

        public void update(Payment p) { rows.put(p.orderId(), p); }

        public List<Payment> findUnsettled(Instant before, int limit) {
            return rows.values().stream().filter(p -> p.status().isUnsettled()
                    || (p.status() == PaymentStatus.AUTHORIZED && p.voidRequested())).toList();
        }

        public void recordAttempt(UUID id, String op, String outcome, String ref, String detail) { attempts.add(op + ":" + outcome); }
    };

    private final PaymentEvents outbox = (type, p) -> events.add(type);
    private final Transactions tx = new Transactions() {
        public synchronized <T> T inTransaction(Supplier<T> work) { return work.get(); }
    };
    private final PaymentService service = new PaymentService(repo, provider, outbox, tx, clock, UUID::randomUUID);
    private final Money amount = Money.of("179.80", "EUR");

    @Test
    void authorizeCaptureRefund() {
        UUID order = UUID.randomUUID();
        assertThat(service.authorize(order, amount, "tok_ok").status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(service.capture(order).status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(service.capture(order).status()).isEqualTo(PaymentStatus.CAPTURED);       // repeat is harmless
        assertThat(service.refund(order).status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(events).containsExactly("payment.PaymentCaptured", "payment.PaymentRefunded");
        assertThat(provider.statusOf(order.toString())).contains("REFUNDED");
    }

    @Test
    void repeatedAuthorizeChargesOnce() {
        UUID order = UUID.randomUUID();
        Payment first = service.authorize(order, amount, "tok_ok");
        Payment second = service.authorize(order, amount, "tok_ok");
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(attempts).containsExactly("AUTHORIZE:SUCCESS");
    }

    @Test
    void declinedCardIsRecordedAndPublished() {
        UUID order = UUID.randomUUID();
        Payment p = service.authorize(order, amount, "tok_decline");
        assertThat(p.status()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(events).containsExactly("payment.PaymentFailed");
        assertThatThrownBy(() -> service.capture(order)).isInstanceOf(DomainException.class);
    }

    @Test
    void lateProviderSuccessAfterTimeoutAndVoidIsVoidedByTheReconciler() throws Exception {
        UUID order = UUID.randomUUID();
        Payment p = service.authorize(order, amount, "tok_timeout");
        assertThat(p.status()).isEqualTo(PaymentStatus.UNKNOWN);                       // caller gets 504

        assertThat(service.voidAuthorization(order).voidRequested()).isTrue();         // order-service asks to void

        Thread.sleep(400);                                                             // provider authorises late
        assertThat(provider.statusOf(order.toString())).contains("AUTHORIZED");

        new PaymentReconciler(repo, service, clock, Duration.ZERO).reconcileBatch(10);
        assertThat(rows.get(order).status()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(provider.statusOf(order.toString())).contains("VOIDED");            // customer not charged
    }

    @Test
    void voidBeforeAuthorizationBlocksALaterAuthorization() {
        UUID order = UUID.randomUUID();
        assertThat(service.voidAuthorization(order).status()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(service.authorize(order, amount, "tok_ok").status()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(provider.statusOf(order.toString())).isEmpty();                     // provider never called
    }

    @Test
    void reconcilerDeclinesWhenTheProviderNeverSawTheAuthorization() {
        UUID order = UUID.randomUUID();
        Payment p = Payment.authorizing(UUID.randomUUID(), order, amount, "tok_ok", clock.instant());
        repo.insert(p);                                                                // crashed before calling the provider
        new PaymentReconciler(repo, service, clock, Duration.ZERO).reconcileBatch(10);
        assertThat(rows.get(order).status()).isEqualTo(PaymentStatus.DECLINED);
    }

    @Test
    void capturedPaymentsAreRefundedNotVoided() {
        UUID order = UUID.randomUUID();
        service.authorize(order, amount, "tok_ok");
        service.capture(order);
        assertThatThrownBy(() -> service.voidAuthorization(order)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.code()).isEqualTo("ALREADY_CAPTURED"));
    }
}
