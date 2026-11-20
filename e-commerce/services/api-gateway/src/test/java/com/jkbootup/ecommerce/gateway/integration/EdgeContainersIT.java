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
    static final AtomicReference<String> TRACEPARENT = new AtomicReference<>();
    static GenericContainer<?> edge;
    static HttpClient https;

    @BeforeAll
    static void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        upstream.createContext("/", ex -> {
            HITS.incrementAndGet();
            TRACEPARENT.set(ex.getRequestHeaders().getFirst("traceparent"));
            LAST.set(ex.getRequestURI() + " id=" + ex.getRequestHeaders().getFirst("X-Request-Id")
                    + " ip=" + ex.getRequestHeaders().getFirst("X-Real-IP"));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            // A storefront product page for anonymous visitors may be cached by the edge for 30 s.
            if (ex.getRequestURI().getPath().startsWith("/p/")) ex.getResponseHeaders().add("X-Accel-Expires", "30");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.start();
        int port = upstream.getAddress().getPort();
        Testcontainers.exposeHostPorts(port);
        edge = new GenericContainer<>(new ImageFromDockerfile("e-commerce/edge-test", false).withFileFromPath(".", Path.of("edge")))
                .withEnv("GATEWAY_UPSTREAM", "host.testcontainers.internal:" + port)
                .withEnv("STOREFRONT_UPSTREAM", "host.testcontainers.internal:" + port)
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

    @Test
    void anonymousPagesAreCachedButNeverForShoppersWithASessionOrCart() throws Exception {
        int before = HITS.get();
        assertThat(get("/p/123").headers().firstValue("X-Cache")).hasValue("MISS");
        HttpResponse<String> cached = get("/p/123");
        assertThat(cached.headers().firstValue("X-Cache")).hasValue("HIT");
        assertThat(cached.headers().firstValue("X-Accel-Expires")).isEmpty();
        assertThat(HITS.get() - before).isEqualTo(1);
        assertThat(get("/p/123", "Cookie", "__Host-sid=abc").headers().firstValue("X-Cache")).hasValue("BYPASS");
        assertThat(get("/p/123", "Cookie", "theme=dark; __Host-guest=0f0f").headers().firstValue("X-Cache")).hasValue("BYPASS");
        assertThat(HITS.get() - before).isEqualTo(3);
    }

    @Test
    void storefrontProbesAreNotPublicAndSecurityHeadersAppearOnce() throws Exception {
        int before = HITS.get();
        for (String probe : new String[]{"/readyz", "/metrics"}) assertThat(get(probe).statusCode()).isEqualTo(404);
        assertThat(HITS.get()).isEqualTo(before);
        HttpResponse<String> page = get("/cart");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().allValues("Strict-Transport-Security")).hasSize(1);
        assertThat(LAST.get()).startsWith("/cart").contains("ip=");
    }

    @Test
    void theFirewallRefusesAttacksBeforeTheyReachTheShopAndLetsOrdinaryRequestsThrough() throws Exception {
        int before = HITS.get();
        assertThat(get("/api/v1/search?q=1%27%20OR%20%271%27%3D%271").statusCode()).isEqualTo(403);              // SQL injection
        assertThat(get("/p/1?ref=%3Cscript%3Ealert(1)%3C%2Fscript%3E").statusCode()).isEqualTo(403);             // XSS
        assertThat(get("/api/v1/products?file=..%2F..%2F..%2Fetc%2Fpasswd").statusCode()).isEqualTo(403);         // path traversal
        assertThat(get("/", "User-Agent", "sqlmap/1.8").statusCode()).isEqualTo(403);                              // known scanner
        assertThat(HITS.get()).isEqualTo(before);

        assertThat(get("/api/v1/search?q=o%27reilly%20t-shirt%20%26%20socks").statusCode()).isEqualTo(200);
        // The sign-in callback carries Keycloak's issuer URL (RFC 9207); elsewhere the same URL in a parameter is refused.
        String issuer = "iss=http%3A%2F%2Flocalhost%3A8180%2Frealms%2Fshop";
        assertThat(get("/auth/callback?state=abc&code=x.y.z&" + issuer).statusCode()).isEqualTo(200);
        assertThat(get("/p/1?" + issuer).statusCode()).isEqualTo(403);
        // A brand that happens to be a sample database's name is ordinary text on the staff product form.
        assertThat(get("/staff/products/new?brand=Northwind").statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/admin/products/42?brand=Northwind").statusCode()).isEqualTo(200);
        assertThat(get("/p/1?brand=Northwind").statusCode()).isEqualTo(403);
        HttpResponse<String> post = https.send(HttpRequest.newBuilder(URI.create("https://localhost:" + edge.getMappedPort(8443) + "/cart"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("intent=add&sku=SKU-1&quantity=2&_csrf=k3J9_x-QzR8vT2mWbYp4LsN7eHc"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(200);
        assertThat(HITS.get() - before).isEqualTo(5);
    }

    @Test
    void theEdgeStartsATraceOrContinuesTheCallersAndPassesItUpstream() throws Exception {
        get("/api/v1/orders");
        assertThat(TRACEPARENT.get()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
        String incoming = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        get("/api/v1/orders", "traceparent", incoming);
        assertThat(TRACEPARENT.get()).startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-").doesNotContain("00f067aa0ba902b7");
    }
}
