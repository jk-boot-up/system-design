package com.jkbootup.ecommerce.payment.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private static Payment authorizing() {
        return Payment.authorizing(UUID.randomUUID(), UUID.randomUUID(), Money.of("10.00", "EUR"), "tok_ok", NOW);
    }

    @Test
    void normalLifecycle() {
        Payment p = authorizing();
        p.authorized("ref", NOW);
        p.captured(NOW);
        p.refunded(NOW);
        assertThat(p.status()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void aPaymentWithAVoidRequestIsNeverCaptured() {
        Payment p = authorizing();
        p.requestVoid(NOW);
        p.authorized("ref", NOW);
        assertThatThrownBy(() -> p.captured(NOW)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.code()).isEqualTo("VOID_REQUESTED"));
    }

    @Test
    void illegalTransitionsAndBadInputAreRejected() {
        Payment p = authorizing();
        p.declined("NSF", NOW);
        assertThatThrownBy(() -> p.authorized("ref", NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Payment.authorizing(UUID.randomUUID(), UUID.randomUUID(), Money.of("0", "EUR"), "tok", NOW))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Payment.authorizing(UUID.randomUUID(), UUID.randomUUID(), Money.of("1", "EUR"), " ", NOW))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void unknownCanSettleEitherWay() {
        Payment a = authorizing();
        a.unknown("TIMEOUT", NOW);
        a.authorized("ref", NOW);
        assertThat(a.status()).isEqualTo(PaymentStatus.AUTHORIZED);
        Payment b = authorizing();
        b.unknown("TIMEOUT", NOW);
        b.declined("NOT_FOUND", NOW);
        assertThat(b.status()).isEqualTo(PaymentStatus.DECLINED);
    }
}
