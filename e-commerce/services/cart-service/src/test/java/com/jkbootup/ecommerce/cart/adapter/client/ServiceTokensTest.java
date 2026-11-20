package com.jkbootup.ecommerce.cart.adapter.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Against a real HTTP token endpoint; time is moved by hand. */
class ServiceTokensTest {

    private HttpServer server;
    private final AtomicInteger issued = new AtomicInteger();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile boolean down;
    private volatile long delayMillis;
    private Instant now = Instant.parse("2026-11-20T10:00:00Z");
    private ServiceTokens tokens;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/token", ex -> {
            authorizations.add(ex.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = down ? "{\"error\":\"temporarily_unavailable\"}".getBytes()
                    : ("{\"access_token\":\"t" + issued.incrementAndGet() + "\",\"expires_in\":300,\"token_type\":\"Bearer\"}").getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(down ? 503 : 200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now; }
        };
        RestClient endpoint = RestClient.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token");
        tokens = new ServiceTokens(endpoint, "order-service", "s3cret", clock, Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void asksWithTheClientCredentialsGrantAndReusesTheTokenUntilTwoThirdsOfItsLife() {
        assertThat(tokens.current()).isEqualTo("t1");
        assertThat(authorizations).containsExactly("Basic b3JkZXItc2VydmljZTpzM2NyZXQ=");   // order-service:s3cret
        assertThat(bodies).containsExactly("grant_type=client_credentials");
        now = now.plusSeconds(199);
        assertThat(tokens.current()).isEqualTo("t1");
        now = now.plusSeconds(2);                                                               // past 200 s of 300
        assertThat(tokens.current()).isEqualTo("t2");
        assertThat(issued).hasValue(2);
    }

    @Test
    void anOutageDuringRenewalKeepsTheCurrentTokenAndRetriesAtMostEveryFiveSeconds() {
        tokens.current();
        down = true;
        now = now.plusSeconds(210);
        assertThat(tokens.current()).isEqualTo("t1");
        assertThat(tokens.current()).isEqualTo("t1");
        assertThat(authorizations).hasSize(2);                       // one failed renewal, then no attempt for 5 s
        now = now.plusSeconds(6);
        assertThat(tokens.current()).isEqualTo("t1");
        assertThat(authorizations).hasSize(3);
        down = false;
        now = now.plusSeconds(6);
        assertThat(tokens.current()).isEqualTo("t2");
    }

    @Test
    void withoutAnyUsableTokenTheCallFails() {
        down = true;
        assertThatThrownBy(tokens::current).isInstanceOf(IllegalStateException.class).hasMessageContaining("No service token");
        down = false;
        tokens.current();
        down = true;
        now = now.plusSeconds(295);                                  // past expiry minus the 10 s margin
        assertThatThrownBy(tokens::current).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void manyConcurrentCallersWithoutATokenCauseOneRequest() throws Exception {
        delayMillis = 300;
        try (ExecutorService pool = Executors.newFixedThreadPool(32)) {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 32; i++) results.add(pool.submit(tokens::current));
            for (Future<String> r : results) assertThat(r.get()).isEqualTo("t1");
        }
        assertThat(issued).hasValue(1);
    }

    @Test
    void theInterceptorTurnsAMissingTokenIntoAnIoFailure() {
        down = true;
        RestClient client = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .requestInterceptor(tokens.interceptor()).build();
        assertThatThrownBy(() -> client.get().uri("/anything").retrieve().toBodilessEntity())
                .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
    }
}
