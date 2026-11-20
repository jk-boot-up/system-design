package com.jkbootup.ecommerce.catalog.integration;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PostgreSQL, Redis, Kafka and S3 (RustFS) containers. Enable with: ./gradlew test -PwithContainers */
@Tag("containers")
@SpringBootTest
@AutoConfigureMockMvc
class CatalogContainersIT {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");
    /** S3-compatible object storage. MinIO no longer publishes community images, so tests use RustFS, which speaks the same API. */
    static final GenericContainer<?> S3 = new GenericContainer<>("rustfs/rustfs:1.0.3").withExposedPorts(9000)
            .withEnv("RUSTFS_ACCESS_KEY", "catalog").withEnv("RUSTFS_SECRET_KEY", "catalog-secret")
            .waitingFor(Wait.forListeningPort());

    static {
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
        S3.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("catalog.outbox.poll-interval", () -> "50ms");
        r.add("catalog.storage.endpoint", () -> "http://" + S3.getHost() + ":" + S3.getMappedPort(9000));
        r.add("catalog.storage.access-key", () -> "catalog");
        r.add("catalog.storage.secret-key", () -> "catalog-secret");
    }

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JsonMapper json;

    static RequestPostProcessor staff() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_staff"));
    }

    String category(String slug) throws Exception {
        String body = mvc.perform(post("/api/v1/admin/categories").with(staff()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"%s\",\"name\":\"%s\"}".formatted(slug, slug)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("id").asString();
    }

    String product(String sku, String categoryId, String price) throws Exception {
        String body = mvc.perform(post("/api/v1/admin/products").with(staff()).contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(sku, categoryId, price)))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"1\""))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("id").asString();
    }

    static String productJson(String sku, String categoryId, String price) {
        return """
                {"sku":"%s","name":"Trail Runner 2","description":"Light trail shoe","categoryId":"%s","brand":"Acme",
                 "price":{"amount":"%s","currency":"EUR"},"attributes":{"size":"42","colour":"blue"},"status":"ACTIVE"}"""
                .formatted(sku, categoryId, price);
    }

    @Test
    void conditionalUpdatesEvictTheCacheAndPublishTheNewVersion() throws Exception {
        String cat = category("trail-shoes");
        String id = product("SKU-ETAG-1", cat, "89.90");

        mvc.perform(get("/api/v1/products/{id}", id)).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\"")).andExpect(jsonPath("$.attributes.colour").value("blue"));
        assertThat(redis.hasKey("product:" + id)).isTrue();

        mvc.perform(put("/api/v1/admin/products/{id}", id).with(staff()).contentType(MediaType.APPLICATION_JSON)
                .content(productJson("SKU-ETAG-1", cat, "79.90"))).andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        mvc.perform(put("/api/v1/admin/products/{id}", id).with(staff()).header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(productJson("SKU-ETAG-1", cat, "79.90")))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""));
        assertThat(redis.hasKey("product:" + id)).isFalse();
        mvc.perform(put("/api/v1/admin/products/{id}", id).with(staff()).header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(productJson("SKU-ETAG-1", cat, "1.00")))
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("VERSION_MISMATCH"));

        mvc.perform(get("/api/v1/products/{id}", id)).andExpect(jsonPath("$.price.amount").value("79.90"))
                .andExpect(jsonPath("$.version").value(2));

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("catalog.events"));
            List<JsonNode> mine = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (mine.size() < 2 && System.nanoTime() < deadline)
                consumer.poll(Duration.ofMillis(300)).forEach(r -> {
                    if (r.key().equals(id)) mine.add(json.readTree(r.value()));
                });
            assertThat(mine).extracting(e -> e.path("payload").path("catalogVersion").asLong()).containsExactly(1L, 2L);
            assertThat(mine.getLast().path("payload").path("categorySlug").asString()).isEqualTo("trail-shoes");
            assertThat(mine.getLast().path("payload").path("price").path("amount").asString()).isEqualTo("79.90");
        }
    }

    @Test
    void imagesAreUploadedStraightToStorageAndConfirmed() throws Exception {
        String id = product("SKU-IMG-1", category("images"), "10.00");
        String ticket = mvc.perform(post("/api/v1/admin/products/{id}/images", id).with(staff()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/png\",\"sizeBytes\":4}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode t = json.readTree(ticket);
        String key = t.path("objectKey").asString();

        mvc.perform(post("/api/v1/admin/products/{id}/images/confirm", id).with(staff()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"objectKey\":\"" + key + "\"}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IMAGE_MISSING"));

        HttpResponse<String> upload = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(t.path("uploadUrl").asString()))
                .header("Content-Type", "image/png").PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1, 2, 3, 4})).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(upload.statusCode()).isEqualTo(200);

        mvc.perform(post("/api/v1/admin/products/{id}/images/confirm", id).with(staff()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectKey\":\"" + key + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.images[0].position").value(1))
                .andExpect(jsonPath("$.images[0].url").value(org.hamcrest.Matchers.endsWith(key)))
                .andExpect(header().string("ETag", "\"2\""));
    }

    @Test
    void pricesForCartAndAccessRules() throws Exception {
        String cat = category("prices");
        product("SKU-PRICE-1", cat, "19.99");
        String skus = "{\"skus\":[\"SKU-PRICE-1\",\"SKU-NONE\"]}";
        // Only cart-service's client-credentials token: not anonymous, not a customer, not another service.
        mvc.perform(post("/internal/products/prices").contentType(MediaType.APPLICATION_JSON).content(skus))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/products/prices").with(jwt().jwt(j -> j.audience(java.util.List.of("shop-api")).claim("azp", "shop-web")))
                        .contentType(MediaType.APPLICATION_JSON).content(skus))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/products/prices").with(jwt().jwt(j -> j.audience(java.util.List.of("catalog-service")).claim("azp", "order-service")))
                        .contentType(MediaType.APPLICATION_JSON).content(skus))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/products/prices").with(jwt().jwt(j -> j.audience(java.util.List.of("catalog-service")).claim("azp", "cart-service")))
                        .contentType(MediaType.APPLICATION_JSON).content(skus))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].price.amount").value("19.99"))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"));

        mvc.perform(post("/api/v1/admin/categories").contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"a\",\"name\":\"A\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/categories").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"a\",\"name\":\"A\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/products").with(staff()).contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("SKU-PRICE-1", cat, "1.00")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SKU_TAKEN"));
        mvc.perform(post("/api/v1/admin/products").with(staff()).contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("SKU-PRICE-2", UUID.randomUUID().toString(), "1.00")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("UNKNOWN_CATEGORY"));
        mvc.perform(get("/api/v1/products/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/categories")).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.slug=='prices')]").exists());
    }
}
