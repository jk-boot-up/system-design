package com.jkbootup.ecommerce.order.integration;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PostgreSQL + Kafka containers, real HTTP to fake downstream services. Enable with: ./gradlew test -PwithContainers */
@Tag("containers")
@SpringBootTest
@AutoConfigureMockMvc
class OrderContainersIT {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");
    static final FakeDownstream DOWNSTREAM;

    static {
        POSTGRES.start();
        KAFKA.start();
        try {
            DOWNSTREAM = new FakeDownstream();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("clients.cart.url", DOWNSTREAM::url);
        r.add("clients.inventory.url", DOWNSTREAM::url);
        r.add("clients.payment.url", DOWNSTREAM::url);
        r.add("order.outbox.poll-interval", () -> "50ms");
    }

    @MockitoBean
    JwtDecoder jwtDecoder;      // tokens are supplied by the jwt() post-processor; no identity provider needed

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    private final UUID customer = UUID.randomUUID();

    private RequestPostProcessor customerToken() {
        return jwt().jwt(j -> j.subject(customer.toString()).claim("email", "ada@example.com"));
    }

    @BeforeEach
    void reset() {
        DOWNSTREAM.outOfStock = false;
        DOWNSTREAM.declinePayment = false;
        DOWNSTREAM.calls.clear();
    }

    @Test
    void placesAnOrderEndToEndAndPublishesOrderConfirmed() throws Exception {
        String body = mvc.perform(post("/api/v1/orders").with(customerToken()).header("Idempotency-Key", "k-happy")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paymentToken\":\"tok_ok\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.total.amount").value("179.80"))
                .andReturn().getResponse().getContentAsString();
        String orderId = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        assertThat(DOWNSTREAM.calls).containsExactly(
                "GET /internal/carts/{id}/checkout-snapshot", "POST /internal/reservations", "POST /internal/payments/authorize",
                "POST /internal/reservations/{id}/commit", "POST /internal/payments/{id}/capture");
        assertThat(jdbc.sql("select count(*) from orders_default").query(Integer.class).single())
                .as("the order went into its monthly partition, not the default one").isZero();

        // replay with the same key: same order, 200, no downstream calls
        DOWNSTREAM.calls.clear();
        mvc.perform(post("/api/v1/orders").with(customerToken()).header("Idempotency-Key", "k-happy")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paymentToken\":\"tok_ok\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(orderId));
        assertThat(DOWNSTREAM.calls).isEmpty();

        // the outbox relay publishes OrderPlaced and OrderConfirmed, keyed by order id
        assertThat(eventsFor(orderId)).containsSubsequence("order.OrderPlaced", "order.OrderConfirmed");
    }

    @Test
    void declinedPaymentAnswers402AndReleasesStock() throws Exception {
        DOWNSTREAM.declinePayment = true;
        mvc.perform(post("/api/v1/orders").with(customerToken()).header("Idempotency-Key", "k-declined")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paymentToken\":\"tok_decline\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"))
                .andExpect(jsonPath("$.order.status").value("CANCELLED"));
        assertThat(DOWNSTREAM.calls).last().isEqualTo("POST /internal/reservations/{id}/release");
    }

    @Test
    void outOfStockAnswers409() throws Exception {
        DOWNSTREAM.outOfStock = true;
        mvc.perform(post("/api/v1/orders").with(customerToken()).header("Idempotency-Key", "k-oos")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paymentToken\":\"tok_ok\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUT_OF_STOCK"));
    }

    @Test
    void historyIsPrivateAndPaginated() throws Exception {
        for (int i = 0; i < 3; i++)
            mvc.perform(post("/api/v1/orders").with(customerToken()).header("Idempotency-Key", "k-hist-" + i)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"paymentToken\":\"tok_ok\"}")).andExpect(status().isCreated());
        String page = mvc.perform(get("/api/v1/orders?limit=2").with(customerToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        String cursor = page.replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/api/v1/orders?limit=2&cursor=" + cursor).with(customerToken()))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.nextCursor").doesNotExist());

        String someone = page.replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/api/v1/orders/" + someone).with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
    }

    @Test
    void customerCancelsAConfirmedOrder() throws Exception {
        String body = mvc.perform(post("/api/v1/orders").with(customerToken()).header("Idempotency-Key", "k-cancel")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paymentToken\":\"tok_ok\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String orderId = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        DOWNSTREAM.calls.clear();
        mvc.perform(post("/api/v1/orders/" + orderId + "/cancel").with(customerToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("CUSTOMER_REQUEST"));
        assertThat(DOWNSTREAM.calls).containsExactly("POST /internal/payments/{id}/refund", "POST /internal/reservations/{id}/return");
        mvc.perform(post("/api/v1/orders").with(customerToken()).header("Idempotency-Key", "bad key!")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paymentToken\":\"tok_ok\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/orders").with(customerToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paymentToken\":\"tok_ok\"}"))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Location"));
    }

    private List<String> eventsFor(String orderId) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<String> types = new ArrayList<>();
        try (var c = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
            c.subscribe(List.of("order.events"));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline && !types.contains("order.OrderConfirmed")) {
                c.poll(Duration.ofMillis(300)).forEach(r -> {
                    if (orderId.equals(r.key())) types.add(new String(r.headers().lastHeader("type").value()));
                });
            }
        }
        return types;
    }
}
