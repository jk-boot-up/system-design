package com.jkbootup.ecommerce.inventory.integration;

import com.jkbootup.ecommerce.inventory.application.ReservationService;
import com.jkbootup.ecommerce.inventory.domain.OrderLine;
import com.jkbootup.ecommerce.inventory.domain.Quantity;
import com.jkbootup.ecommerce.inventory.domain.ReserveOutcome;
import com.jkbootup.ecommerce.inventory.domain.Sku;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against real PostgreSQL and Kafka. Enable with: ./gradlew test -PwithContainers */
@Tag("containers")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InventoryContainersIT {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("inventory.outbox.poll-interval", () -> "50ms");
    }

    @Autowired
    ReservationService service;

    @Value("${local.server.port}")
    int port;

    @Test
    void neverOversellsUnderConcurrentOrders() throws Exception {
        Sku sku = Sku.of("SKU-HOT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        service.setAvailable(sku, 10);
        List<Callable<ReserveOutcome>> attempts = new ArrayList<>();
        for (int i = 0; i < 50; i++)
            attempts.add(() -> service.reserve(UUID.randomUUID(), List.of(new OrderLine(sku, Quantity.of(1)))));
        List<ReserveOutcome> outcomes = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<ReserveOutcome> f : pool.invokeAll(attempts)) outcomes.add(f.get());
        }
        assertThat(outcomes.stream().filter(o -> o instanceof ReserveOutcome.Reserved)).hasSize(10);
        assertThat(outcomes.stream().filter(o -> o instanceof ReserveOutcome.OutOfStock)).hasSize(40);
        var level = service.level(sku).orElseThrow();
        assertThat(level.available()).isZero();
        assertThat(level.reserved()).isEqualTo(10);
    }

    @Test
    void restContractReservesAndRejectsWithProblemDetails() {
        RestClient http = RestClient.create("http://localhost:" + port);
        http.put().uri("/internal/stock/SKU-REST").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("available", 3)).retrieve().toBodilessEntity();
        UUID order = UUID.randomUUID();
        Map<String, Object> ok = http.post().uri("/internal/reservations").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("orderId", order, "lines", List.of(Map.of("sku", "SKU-REST", "quantity", 2))))
                .retrieve().body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(ok).containsEntry("status", "RESERVED");

        assertThatThrownBy(() -> http.post().uri("/internal/reservations").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("orderId", UUID.randomUUID(), "lines", List.of(Map.of("sku", "SKU-REST", "quantity", 2))))
                .retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(HttpClientErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getResponseBodyAsString()).contains("\"code\":\"OUT_OF_STOCK\"").contains("SKU-REST");
                });

        http.post().uri("/internal/reservations/{id}/release", order).retrieve().toBodilessEntity();
        assertThat(service.level(Sku.of("SKU-REST")).orElseThrow().available()).isEqualTo(3);
    }

    @Test
    void publishesStockChangedThroughTheOutbox() {
        service.setAvailable(Sku.of("SKU-EVENT"), 7);
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("inventory.events"));
            List<String> seen = new ArrayList<>();
            waitUntil(() -> {
                consumer.poll(Duration.ofMillis(300)).forEach(r -> seen.add(r.key() + " " + r.value()));
                // jsonb re-formats the JSON ("available": 7), so match on content, not spacing
                return seen.stream().anyMatch(v -> v.startsWith("SKU-EVENT ") && v.matches("(?s).*\"available\":\\s*7[,}].*")
                        && v.contains("inventory.StockChanged"));
            });
        }
    }

    @Test
    void releasesStockWhenOrderCancelledEventArrives() {
        Sku sku = Sku.of("SKU-CANCEL");
        service.setAvailable(sku, 4);
        UUID order = UUID.randomUUID();
        service.reserve(order, List.of(new OrderLine(sku, Quantity.of(3))));
        String event = """
                {"eventId":"%s","type":"order.OrderCancelled","version":1,"occurredAt":"2026-11-01T10:00:00Z",
                 "aggregateId":"%s","payload":{"orderId":"%s","reason":"PAYMENT_DECLINED"}}""".formatted(UUID.randomUUID(), order, order);
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (var producer = new KafkaProducer<>(props, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>("order.events", order.toString(), event));
            producer.send(new ProducerRecord<>("order.events", order.toString(), event));   // duplicate delivery
        }
        waitUntil(() -> service.level(sku).orElseThrow().available() == 4);
        assertThat(service.level(sku).orElseThrow().reserved()).isZero();
    }

    private static void waitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met within 30 s");
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
