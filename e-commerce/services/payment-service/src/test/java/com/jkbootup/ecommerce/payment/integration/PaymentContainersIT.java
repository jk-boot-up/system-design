package com.jkbootup.ecommerce.payment.integration;

import com.jkbootup.ecommerce.payment.application.PaymentReconciler;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PostgreSQL + Kafka. Enable with: ./gradlew test -PwithContainers */
@Tag("containers")
@SpringBootTest(properties = {"payment.provider.timeout=200ms", "payment.provider.slow-delay=600ms",
        "payment.reconcile.settle-after=0s", "payment.reconcile.interval=1h"})
@AutoConfigureMockMvc
class PaymentContainersIT {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("payment.outbox.poll-interval", () -> "50ms");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    PaymentReconciler reconciler;

    @Autowired
    JdbcClient jdbc;

    private String authorize(UUID order, String token) {
        return """
                {"orderId":"%s","amount":{"amount":"179.80","currency":"EUR"},"paymentToken":"%s"}""".formatted(order, token);
    }

    @Test
    void statusCodesEncodeTheOutcome() throws Exception {
        UUID ok = UUID.randomUUID(), declined = UUID.randomUUID(), slow = UUID.randomUUID();
        mvc.perform(post("/internal/payments/authorize").contentType(MediaType.APPLICATION_JSON).content(authorize(ok, "tok_ok")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AUTHORIZED"));
        mvc.perform(post("/internal/payments/authorize").contentType(MediaType.APPLICATION_JSON).content(authorize(declined, "tok_decline")))
                .andExpect(status().isPaymentRequired()).andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"));
        mvc.perform(post("/internal/payments/authorize").contentType(MediaType.APPLICATION_JSON).content(authorize(slow, "tok_timeout")))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("PAYMENT_UNKNOWN"));
        mvc.perform(post("/internal/payments/authorize").contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lateSuccessIsVoidedSoTheCustomerIsNotCharged() throws Exception {
        UUID order = UUID.randomUUID();
        mvc.perform(post("/internal/payments/authorize").contentType(MediaType.APPLICATION_JSON).content(authorize(order, "tok_timeout")))
                .andExpect(status().isGatewayTimeout());
        mvc.perform(post("/internal/payments/{id}/void", order)).andExpect(status().isOk())
                .andExpect(jsonPath("$.voidRequested").value(true));
        Thread.sleep(800);                       // the provider answers late with a success
        reconciler.reconcileBatch(50);
        assertThat(jdbc.sql("select status from payments where order_id = :o").param("o", order).query(String.class).single())
                .isEqualTo("VOIDED");
        // the audit trail shows every provider interaction (reconcile and void share one transaction, hence any order)
        assertThat(jdbc.sql("select a.operation || ':' || a.outcome from payment_attempts a " +
                        "join payments p on p.id = a.payment_id where p.order_id = :o").param("o", order).query(String.class).list())
                .containsExactlyInAnyOrder("AUTHORIZE:TIMEOUT", "RECONCILE:SUCCESS", "VOID:SUCCESS");
    }

    @Test
    void captureAndRefundArePublished() throws Exception {
        UUID order = UUID.randomUUID();
        mvc.perform(post("/internal/payments/authorize").contentType(MediaType.APPLICATION_JSON).content(authorize(order, "tok_ok")))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/payments/{id}/capture", order)).andExpect(jsonPath("$.status").value("CAPTURED"));
        mvc.perform(post("/internal/payments/{id}/capture", order)).andExpect(jsonPath("$.status").value("CAPTURED"));
        mvc.perform(post("/internal/payments/{id}/refund", order)).andExpect(jsonPath("$.status").value("REFUNDED"));
        mvc.perform(post("/internal/payments/{id}/void", order)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
        assertThat(eventsFor(order.toString())).containsExactly("payment.PaymentCaptured", "payment.PaymentRefunded");
    }

    private List<String> eventsFor(String key) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<String> types = new ArrayList<>();
        try (var c = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
            c.subscribe(List.of("payment.events"));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline && types.size() < 2)
                c.poll(Duration.ofMillis(300)).forEach(r -> {
                    if (key.equals(r.key())) types.add(new String(r.headers().lastHeader("type").value()));
                });
        }
        return types;
    }
}
