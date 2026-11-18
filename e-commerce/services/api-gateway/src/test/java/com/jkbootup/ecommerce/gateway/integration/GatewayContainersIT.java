package com.jkbootup.ecommerce.gateway.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Redis container and stub services over real HTTP. Enable with: ./gradlew test -PwithContainers */
@Tag("containers")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.rate-limits.hot-sku.rate=1", "gateway.rate-limits.hot-sku.burst=2"})
class GatewayContainersIT {

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);
    /** Last request each stub saw: path and X-Request-Id. */
    static final Map<String, String[]> SEEN = new ConcurrentHashMap<>();
    static final Map<String, Integer> PORTS = new ConcurrentHashMap<>();

    static {
        REDIS.start();
        for (String service : List.of("catalog", "review", "cart", "order", "inventory", "recommendation")) {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/", ex -> {
                    SEEN.put(service, new String[]{ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst("X-Request-Id")});
                    byte[] body = ("{\"service\":\"" + service + "\"}").getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, body.length);
                    ex.getResponseBody().write(body);
                    ex.close();
                });
                server.start();
                PORTS.put(service, server.getAddress().getPort());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        PORTS.forEach((s, p) -> r.add(s.toUpperCase() + "_URL", () -> "http://127.0.0.1:" + p));
        r.add("SEARCH_URL", () -> "http://127.0.0.1:1");                     // nothing listens: search is down
    }

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @LocalServerPort
    int port;

    WebTestClient client() {
        when(jwtDecoder.decode(anyString())).thenAnswer(inv -> {
            String token = inv.getArgument(0);
            List<String> roles = token.startsWith("staff") ? List.of("staff") : List.of();
            return Mono.just(Jwt.withTokenValue(token).header("alg", "none").subject(token.substring(token.indexOf('.') + 1))
                    .claim("roles", roles).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build());
        });
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    static String bearer(String role) {
        return "Bearer " + role + "." + UUID.randomUUID();
    }

    @Test
    void routesReachTheRightServiceAndRequestIdsFlowThrough() {
        WebTestClient c = client();
        c.get().uri("/api/v1/products/SKU-1001/reviews").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("review");
        c.get().uri("/api/v1/products/SKU-1001/recommendations").exchange().expectBody().jsonPath("$.service").isEqualTo("recommendation");
        String id = c.get().uri("/api/v1/products/42").header("X-Request-Id", "ticket-12345678").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", "ticket-12345678")
                .expectBody().jsonPath("$.service").isEqualTo("catalog").returnResult().getResponseHeaders().getFirst("X-Request-Id");
        assertThat(SEEN.get("catalog")[1]).isEqualTo(id);
        String generated = c.get().uri("/api/v1/categories").header("X-Request-Id", "bad id!").exchange()
                .returnResult(String.class).getResponseHeaders().getFirst("X-Request-Id");
        assertThat(generated).isNotEqualTo("bad id!").hasSize(36);

        c.put().uri("/api/v1/admin/stock/SKU-1001").header(HttpHeaders.AUTHORIZATION, bearer("staff"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"available\":5}").exchange().expectStatus().isOk();
        assertThat(SEEN.get("inventory")[0]).isEqualTo("/internal/stock/SKU-1001");
    }

    @Test
    void accessRulesAtTheEdge() {
        WebTestClient c = client();
        c.post().uri("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isUnauthorized();
        c.put().uri("/api/v1/admin/products/42").header(HttpHeaders.AUTHORIZATION, bearer("customer"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isForbidden();
        c.get().uri("/internal/carts/x/checkout-snapshot").header(HttpHeaders.AUTHORIZATION, bearer("staff")).exchange()
                .expectStatus().isForbidden();
        c.get().uri("/api/v1/cart").header("X-Guest-Id", UUID.randomUUID().toString()).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("cart");
    }

    @Test
    void checkoutIsLimitedPerUserWithRetryAfter() {
        WebTestClient c = client();
        String ada = bearer("customer");
        for (int i = 0; i < 3; i++)
            c.post().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, ada).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}").exchange().expectStatus().isOk();
        c.post().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, ada).contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "5")
                .expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED");
        c.post().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer("customer")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}").exchange().expectStatus().isOk();                 // another customer is unaffected
    }

    @Test
    void aHotSkuIsLimitedAcrossAllShoppers() {
        WebTestClient c = client();
        for (int i = 0; i < 2; i++)
            c.put().uri("/api/v1/cart/items/SKU-FLASH").header("X-Guest-Id", UUID.randomUUID().toString())
                    .header("X-Real-IP", "10.0.0." + i).contentType(MediaType.APPLICATION_JSON).bodyValue("{\"quantity\":1}")
                    .exchange().expectStatus().isOk();
        c.put().uri("/api/v1/cart/items/SKU-FLASH").header("X-Guest-Id", UUID.randomUUID().toString()).header("X-Real-IP", "10.0.0.9")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"quantity\":1}")
                .exchange().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        c.put().uri("/api/v1/cart/items/SKU-OTHER").header("X-Guest-Id", UUID.randomUUID().toString()).header("X-Real-IP", "10.0.0.9")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"quantity\":1}").exchange().expectStatus().isOk();
    }

    @Test
    void aDownServiceGetsAFastProblemResponse() {
        client().get().uri("/api/v1/search?q=trail").exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "5")
                .expectBody().jsonPath("$.code").isEqualTo("SEARCH_UNAVAILABLE");
    }
}
