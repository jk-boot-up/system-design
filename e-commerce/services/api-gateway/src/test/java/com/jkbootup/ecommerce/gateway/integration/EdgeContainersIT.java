package com.jkbootup.ecommerce.gateway.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The NGINX edge image in front of a stub gateway. Enable with: ./gradlew test -PwithContainers */
@Tag("containers")
class EdgeContainersIT {

    static HttpServer upstream;
    static final AtomicInteger HITS = new AtomicInteger();
    static final AtomicReference<String> LAST = new AtomicReference<>();
    static GenericContainer<?> edge;
    static HttpClient https;

    @BeforeAll
    static void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        upstream.createContext("/", ex -> {
            HITS.incrementAndGet();
            LAST.set(ex.getRequestURI() + " id=" + ex.getRequestHeaders().getFirst("X-Request-Id")
                    + " ip=" + ex.getRequestHeaders().getFirst("X-Real-IP"));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.start();
        int port = upstream.getAddress().getPort();
        Testcontainers.exposeHostPorts(port);
        edge = new GenericContainer<>(new ImageFromDockerfile("e-commerce/edge-test", false).withFileFromPath(".", Path.of("edge")))
                .withEnv("GATEWAY_UPSTREAM", "host.testcontainers.internal:" + port)
                .withExposedPorts(8080, 8443)
                .waitingFor(Wait.forHttp("/healthz").forPort(8080));
        edge.start();

        SSLContext trustAll = SSLContext.getInstance("TLS");
        trustAll.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        https = HttpClient.newBuilder().sslContext(trustAll).version(HttpClient.Version.HTTP_2).build();
    }

    @AfterAll
    static void stop() {
        if (edge != null) edge.stop();
        if (upstream != null) upstream.stop(0);
    }

    static HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("https://localhost:" + edge.getMappedPort(8443) + path));
        if (headers.length > 0) b.headers(headers);
        return https.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anonymousReadsAreMicroCachedOverHttp2() throws Exception {
        int before = HITS.get();
        HttpResponse<String> first = get("/api/v1/products/42");
        HttpResponse<String> second = get("/api/v1/products/42");
        assertThat(first.version()).isEqualTo(HttpClient.Version.HTTP_2);
        assertThat(first.headers().firstValue("X-Cache")).hasValue("MISS");
        assertThat(second.headers().firstValue("X-Cache")).hasValue("HIT");
        assertThat(HITS.get() - before).isEqualTo(1);
        assertThat(first.headers().firstValue("Strict-Transport-Security")).isPresent();
    }

    @Test
    void signedInRequestsBypassTheCache() throws Exception {
        int before = HITS.get();
        get("/api/v1/products/77", "Authorization", "Bearer abc");
        HttpResponse<String> again = get("/api/v1/products/77", "Authorization", "Bearer abc");
        assertThat(again.headers().firstValue("X-Cache")).hasValue("BYPASS");
        assertThat(HITS.get() - before).isEqualTo(2);
    }

    @Test
    void everyRequestCarriesARequestIdAndTheClientAddress() throws Exception {
        HttpResponse<String> r = get("/api/v1/orders");
        String id = r.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(id).matches("[0-9a-f]{32}");
        assertThat(LAST.get()).contains("id=" + id).contains("ip=");
        assertThat(get("/api/v1/orders", "X-Request-Id", "ticket-12345678").headers().firstValue("X-Request-Id"))
                .hasValue("ticket-12345678");
    }

    @Test
    void internalApisAreNeverExposedAndPlainHttpRedirects() throws Exception {
        int before = HITS.get();
        assertThat(get("/internal/carts/x/checkout-snapshot").statusCode()).isEqualTo(404);
        assertThat(HITS.get()).isEqualTo(before);
        HttpResponse<String> plain = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + edge.getMappedPort(8080) + "/api/v1/products/1")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(plain.statusCode()).isEqualTo(301);
        assertThat(plain.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).startsWith("https://"));
    }
}
