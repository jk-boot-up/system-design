package com.jkbootup.ecommerce.search.integration;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** OpenSearch + Kafka containers; events are produced in deliberately bad orders. Enable with: ./gradlew test -PwithContainers */
@Tag("containers")
@SpringBootTest(properties = "search.opensearch.refresh-on-write=true")
@AutoConfigureMockMvc
class SearchContainersIT {

    static final GenericContainer<?> OPENSEARCH = new GenericContainer<>("opensearchproject/opensearch:3.3.2")
            .withExposedPorts(9200)
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .waitingFor(Wait.forHttp("/_cluster/health?wait_for_status=yellow").forPort(9200).withStartupTimeout(Duration.ofMinutes(3)));
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static {
        OPENSEARCH.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("search.opensearch.url", () -> "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200));
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    static KafkaProducer<String, String> producer;

    @BeforeAll
    static void producer() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        producer = new KafkaProducer<>(p, new StringSerializer(), new StringSerializer());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    static void send(String topic, String key, String value) throws Exception {
        producer.send(new ProducerRecord<>(topic, key, value)).get();
    }

    static String product(String id, String sku, String name, String brand, String category, String price, String status, long version,
                          String occurredAt) {
        return """
                {"eventId":"%s","type":"catalog.ProductChanged","version":1,"occurredAt":"%s","aggregateId":"%s",
                 "payload":{"productId":"%s","sku":"%s","name":"%s","description":"","categorySlug":"%s","brand":"%s",
                 "price":{"amount":"%s","currency":"EUR"},"attributes":{},"status":"%s","imageKeys":[],"catalogVersion":%d}}"""
                .formatted(UUID.randomUUID(), occurredAt, id, id, sku, name, category, brand, price, status, version);
    }

    static String stock(String sku, int available, long version) {
        return """
                {"eventId":"%s","type":"inventory.StockChanged","payload":{"sku":"%s","available":%d,"inStock":%s,"stockVersion":%d}}"""
                .formatted(UUID.randomUUID(), sku, available, available > 0, version);
    }

    JsonNode search(String query) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/search" + query)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    static void waitUntil(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (!condition.call()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in time");
            Thread.sleep(200);
        }
    }

    @Test
    void outOfOrderEventsNeverOverwriteNewerData() throws Exception {
        String id = UUID.randomUUID().toString();
        // Stock arrives before the product exists, then the product's version 2 arrives before version 1, then stock 5 before 4.
        send("inventory.events", "SKU-OOO", stock("SKU-OOO", 4, 7));
        send("catalog.events", id, product(id, "SKU-OOO", "Ridge Runner", "Peak", "trail", "79.90", "ACTIVE", 2, "2026-11-18T10:00:02Z"));
        send("catalog.events", id, product(id, "SKU-OOO", "Ridge Runner OLD", "Peak", "trail", "99.90", "ACTIVE", 1, "2026-11-18T10:00:01Z"));
        send("inventory.events", "SKU-OOO", stock("SKU-OOO", 0, 9));
        send("inventory.events", "SKU-OOO", stock("SKU-OOO", 4, 8));
        // a marker after all of them: once it is visible, every earlier event on these partitions has been processed
        String marker = UUID.randomUUID().toString();
        send("catalog.events", id, product(id, "SKU-OOO", "Ridge Runner", "Peak", "trail", "79.90", "ACTIVE", 2, "2026-11-18T10:00:02Z"));
        send("catalog.events", marker, product(marker, "SKU-MARK", "Marker", "Mark", "marker", "1.00", "ACTIVE", 1, "2026-11-18T10:00:00Z"));
        send("inventory.events", "SKU-MARK", stock("SKU-MARK", 1, 1));
        waitUntil(() -> search("?category=marker").path("items").path(0).path("inStock").asBoolean(false));
        waitUntil(() -> search("?q=ridge").path("total").asLong() == 1);

        JsonNode hit = search("?q=ridge").path("items").path(0);
        assertThat(hit.path("name").asString()).isEqualTo("Ridge Runner");
        assertThat(hit.path("price").path("amount").asString()).isEqualTo("79.90");
        assertThat(hit.path("inStock").asBoolean()).isFalse();                  // stock version 9 (0 left) beat version 8
        assertThat(search("?q=ridge&inStock=true").path("total").asLong()).isZero();
    }

    @Test
    void facetsFiltersSortingAndCursorPages() throws Exception {
        String[][] shoes = {{"Alpine One", "Acme", "120.00"}, {"Alpine Two", "Acme", "60.00"}, {"Alpine Three", "Bolt", "90.00"},
                {"Alpine Four", "Bolt", "30.00"}, {"Alpine Five", "Core", "75.00"}};
        int n = 0;
        for (String[] s : shoes) {
            String id = UUID.randomUUID().toString();
            String sku = "SKU-ALP-" + (++n);
            send("catalog.events", id, product(id, sku, s[0], s[1], "alpine", s[2], "ACTIVE", 1, "2026-11-18T10:0" + n + ":00Z"));
            send("inventory.events", sku, stock(sku, 5, 1));
        }
        String draft = UUID.randomUUID().toString();
        send("catalog.events", draft, product(draft, "SKU-ALP-DRAFT", "Alpine Draft", "Acme", "alpine", "10.00", "DRAFT", 1, "2026-11-18T11:00:00Z"));
        waitUntil(() -> search("?category=alpine").path("total").asLong() == 5);

        JsonNode acme = search("?category=alpine&brand=Acme");
        assertThat(acme.path("total").asLong()).isEqualTo(2);                   // hits are filtered by brand…
        assertThat(acme.path("facets").path("brand").size()).isEqualTo(3);      // …but brand counts still show every brand
        assertThat(search("?category=alpine&minPrice=50&maxPrice=100").path("total").asLong()).isEqualTo(3);

        List<String> prices = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = search("?category=alpine&sort=price_asc&limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
            page.path("items").forEach(i -> {
                prices.add(i.path("price").path("amount").asString());
                assertThat(seen.add(i.path("sku").asString())).isTrue();
            });
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asString();
            pages++;
        } while (cursor != null);
        assertThat(pages).isEqualTo(3);
        assertThat(prices).containsExactly("30.00", "60.00", "75.00", "90.00", "120.00");

        mvc.perform(get("/api/v1/search?cursor=bm90LWEtY3Vyc29y")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        mvc.perform(get("/api/v1/search?limit=500")).andExpect(status().isBadRequest());
    }

    @Test
    void unreadableEventsGoToTheDeadLetterTopic() throws Exception {
        send("catalog.events", "bad-1", "{\"type\":\"catalog.ProductChanged\",\"payload\":{\"sku\":\"BROKEN\"}}");
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("catalog.events.dlq"));
            List<String> keys = new ArrayList<>();
            waitUntil(() -> {
                consumer.poll(Duration.ofMillis(300)).forEach(r -> keys.add(r.key()));
                return keys.contains("bad-1");
            });
        }
    }
}
