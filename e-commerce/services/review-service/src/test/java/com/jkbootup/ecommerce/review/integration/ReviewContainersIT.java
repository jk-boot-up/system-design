package com.jkbootup.ecommerce.review.integration;

import com.datastax.oss.driver.api.core.CqlSession;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cassandra + Kafka containers. Enable with: ./gradlew test -PwithContainers */
@Tag("containers")
@SpringBootTest
@AutoConfigureMockMvc
class ReviewContainersIT {

    static final CassandraContainer CASSANDRA = new CassandraContainer("cassandra:5.0.6");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static {
        CASSANDRA.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.cassandra.contact-points", () -> CASSANDRA.getHost() + ":" + CASSANDRA.getMappedPort(9042));
        r.add("spring.cassandra.local-datacenter", CASSANDRA::getLocalDatacenter);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    @Autowired
    CqlSession session;

    static String body(int rating, String title) {
        return "{\"rating\":%d,\"title\":\"%s\",\"body\":\"\"}".formatted(rating, title);
    }

    static void waitUntil(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.call()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in time");
            Thread.sleep(200);
        }
    }

    @Test
    void verifiedBadgeComesFromAConfirmedOrderAndASecondReviewIsRejected() throws Exception {
        UUID ada = UUID.randomUUID();
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (var producer = new KafkaProducer<>(p, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>("order.events", "o1", """
                    {"eventId":"%s","type":"order.OrderConfirmed","occurredAt":"2026-11-18T10:00:00Z",
                     "payload":{"orderId":"%s","customerId":"%s","lines":[{"sku":"SKU-VER-1","quantity":1}]}}"""
                    .formatted(UUID.randomUUID(), UUID.randomUUID(), ada))).get();
        }
        waitUntil(() -> session.execute("SELECT sku FROM reviews.purchases_by_customer WHERE customer_id = ? AND sku = 'SKU-VER-1'", ada).one() != null);

        mvc.perform(post("/api/v1/products/SKU-VER-1/reviews").with(jwt().jwt(j -> j.subject(ada.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(body(5, "Grippy")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.verified").value(true));
        mvc.perform(post("/api/v1/products/SKU-VER-1/reviews").with(jwt().jwt(j -> j.subject(ada.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(body(1, "Second try")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_REVIEWED"));
        UUID bob = UUID.randomUUID();
        mvc.perform(post("/api/v1/products/SKU-VER-1/reviews").with(jwt().jwt(j -> j.subject(bob.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(body(3, "Fine")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.verified").value(false));

        mvc.perform(get("/api/v1/products/SKU-VER-1/reviews")).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.count").value(2)).andExpect(jsonPath("$.summary.average").value(4.0))
                .andExpect(jsonPath("$.items[0].title").value("Fine"));

        Properties c = new Properties();
        c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        c.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(c, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("review.events"));
            List<String> values = new ArrayList<>();
            waitUntil(() -> {
                consumer.poll(Duration.ofMillis(300)).forEach(r -> { if (r.key().equals("SKU-VER-1")) values.add(r.value()); });
                return values.size() >= 2;
            });
            assertThat(values).allMatch(v -> v.contains("review.ReviewPosted"));
        }
    }

    @Test
    void tenConcurrentPostsBySameCustomerCountExactlyOnce() throws Exception {
        UUID ada = UUID.randomUUID();
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                int n = i;
                results.add(pool.submit(() -> mvc.perform(post("/api/v1/products/SKU-RACE-1/reviews")
                                .with(jwt().jwt(j -> j.subject(ada.toString()))).contentType(MediaType.APPLICATION_JSON)
                                .content(body(1 + n % 5, "Attempt " + n)))
                        .andReturn().getResponse().getStatus()));
            }
            for (Future<Integer> f : results) statuses.add(f.get());
        }
        // A concurrent attempt can find the claim before it is counted and help finish it (201, same review), or after (409).
        assertThat(statuses).allMatch(s -> s == 201 || s == 409).contains(201);
        mvc.perform(get("/api/v1/products/SKU-RACE-1/reviews"))
                .andExpect(jsonPath("$.summary.count").value(1)).andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void pagingAndAccessRules() throws Exception {
        for (int i = 0; i < 5; i++) {
            UUID customer = UUID.randomUUID();
            mvc.perform(post("/api/v1/products/SKU-PAGE-1/reviews").with(jwt().jwt(j -> j.subject(customer.toString())))
                    .contentType(MediaType.APPLICATION_JSON).content(body(4, "Review " + i))).andExpect(status().isCreated());
        }
        String first = mvc.perform(get("/api/v1/products/SKU-PAGE-1/reviews?limit=3")).andExpect(jsonPath("$.items.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        String cursor = first.replaceAll("(?s).*\"nextCursor\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/api/v1/products/SKU-PAGE-1/reviews?limit=3&cursor=" + cursor))
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.nextCursor").isEmpty())
                .andExpect(jsonPath("$.items[1].title").value("Review 0"));

        mvc.perform(post("/api/v1/products/SKU-PAGE-1/reviews").contentType(MediaType.APPLICATION_JSON).content(body(4, "x")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/products/SKU-PAGE-1/reviews").with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())))
                .contentType(MediaType.APPLICATION_JSON).content(body(9, "x"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/products/SKU-NONE/reviews")).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.count").value(0)).andExpect(jsonPath("$.items.length()").value(0));
    }
}
