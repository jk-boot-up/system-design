package com.jkbootup.ecommerce.cart.integration;

import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Redis + Kafka containers and a fake catalog over real HTTP. Enable with: ./gradlew test -PwithContainers */
@Tag("containers")
@SpringBootTest(properties = "cart.price-cache=1ms")
@AutoConfigureMockMvc
class CartContainersIT {

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");
    static volatile boolean catalogUp = true;
    static final HttpServer CATALOG;

    static {
        REDIS.start();
        KAFKA.start();
        try {
            CATALOG = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            CATALOG.createContext("/token", ex -> {   // the realm's token endpoint (client-credentials grant)
                byte[] body = "{\"access_token\":\"cart-svc\",\"expires_in\":300}".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            CATALOG.createContext("/internal/products/prices", ex -> {
                if (!"Bearer cart-svc".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                    ex.sendResponseHeaders(401, -1);   // catalog accepts only cart-service's token
                    ex.close();
                    return;
                }
                byte[] body = """
                        {"items":[{"sku":"SKU-1001","name":"Trail Runner","price":{"amount":"89.90","currency":"EUR"},"status":"ACTIVE"}]}"""
                        .getBytes(StandardCharsets.UTF_8);
                int status = catalogUp ? 200 : 503;
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status, catalogUp ? body.length : -1);
                if (catalogUp) ex.getResponseBody().write(body);
                ex.close();
            });
            CATALOG.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("clients.catalog.url", () -> "http://127.0.0.1:" + CATALOG.getAddress().getPort());
        r.add("service-auth.token-uri", () -> "http://127.0.0.1:" + CATALOG.getAddress().getPort() + "/token");
        r.add("service-auth.client-secret", () -> "test-secret");
    }

    /** order-service's client-credentials token, as the realm issues it. */
    private static RequestPostProcessor orderService() {
        return jwt().jwt(j -> j.audience(List.of("cart-service")).claim("azp", "order-service"));
    }

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    @Autowired
    StringRedisTemplate redis;

    @Test
    void guestCartIsPricedStoredWithExpiryAndMergedOnSignIn() throws Exception {
        UUID guest = UUID.randomUUID(), customer = UUID.randomUUID();
        catalogUp = true;
        mvc.perform(put("/api/v1/cart/items/SKU-1001").header("X-Guest-Id", guest).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total.amount").value("179.80"))
                .andExpect(jsonPath("$.pricing").value("CURRENT"));
        assertThat(redis.getExpire("cart:g:" + guest)).isGreaterThan(Duration.ofDays(29).toSeconds());

        mvc.perform(post("/api/v1/cart/merge").with(jwt().jwt(j -> j.subject(customer.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"guestId\":\"" + guest + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].quantity").value(2));
        assertThat(redis.hasKey("cart:g:" + guest)).isFalse();

        mvc.perform(get("/internal/carts/{c}/checkout-snapshot", customer).with(orderService()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].unitPrice.amount").value("89.90"));
    }

    @Test
    void catalogOutageDegradesTheCartAndBlocksCheckout() throws Exception {
        UUID customer = UUID.randomUUID();
        catalogUp = true;
        mvc.perform(put("/api/v1/cart/items/SKU-1001").with(jwt().jwt(j -> j.subject(customer.toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}")).andExpect(status().isOk());
        catalogUp = false;
        mvc.perform(get("/api/v1/cart").with(jwt().jwt(j -> j.subject(customer.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pricing").value("PENDING"))
                .andExpect(jsonPath("$.checkoutReady").value(false));
        mvc.perform(get("/internal/carts/{c}/checkout-snapshot", customer).with(orderService()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("PRICING_UNAVAILABLE"));
        catalogUp = true;
    }

    @Test
    void onlyOrderServicesTokenMayReadACheckoutSnapshot() throws Exception {
        UUID customer = UUID.randomUUID();
        String path = "/internal/carts/" + customer + "/checkout-snapshot";
        mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        // A customer's own token (audience: the public API) is not enough, even for their own cart.
        mvc.perform(get(path).with(jwt().jwt(j -> j.subject(customer.toString()).audience(List.of("shop-api")).claim("azp", "shop-web"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // Another service's token, or order-service's token meant for another service.
        mvc.perform(get(path).with(jwt().jwt(j -> j.audience(List.of("cart-service")).claim("azp", "cart-service"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).with(jwt().jwt(j -> j.audience(List.of("payment-service")).claim("azp", "order-service"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).with(orderService())).andExpect(status().isOk());
    }

    @Test
    void orderConfirmedEmptiesTheCart() throws Exception {
        UUID customer = UUID.randomUUID();
        catalogUp = true;
        mvc.perform(put("/api/v1/cart/items/SKU-1001").with(jwt().jwt(j -> j.subject(customer.toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}")).andExpect(status().isOk());
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (var producer = new KafkaProducer<>(p, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>("order.events", "o1", """
                    {"eventId":"%s","type":"order.OrderConfirmed","payload":{"orderId":"%s","customerId":"%s"}}"""
                    .formatted(UUID.randomUUID(), UUID.randomUUID(), customer)));
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (Boolean.TRUE.equals(redis.hasKey("cart:c:" + customer)) && System.nanoTime() < deadline) Thread.sleep(200);
        assertThat(redis.hasKey("cart:c:" + customer)).isFalse();
    }

    @Test
    void validationAndOwnership() throws Exception {
        mvc.perform(get("/api/v1/cart")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OWNER_REQUIRED"));
        mvc.perform(put("/api/v1/cart/items/SKU-1").header("X-Guest-Id", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":100}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/cart/merge").contentType(MediaType.APPLICATION_JSON).content("{\"guestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized());
    }
}
