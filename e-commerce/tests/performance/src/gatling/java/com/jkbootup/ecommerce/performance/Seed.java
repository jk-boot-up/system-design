package com.jkbootup.ecommerce.performance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Test data, created through the public API before a simulation starts (Gatling's {@code before} hook): a category of
 * products with plenty of stock, and shopper accounts with tokens. Every run uses fresh SKUs, so runs never collide and
 * stock never runs out; shopper accounts are reused across runs.
 */
public final class Seed {

    public record Product(String id, String sku, String name) {}

    public record Shopper(String username, String token) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] BRANDS = {"Acme", "Northwind", "Contoso", "Fabrikam", "Tailspin"};
    private static final String[] KINDS = {"Trail Runner", "Road Racer", "Hiking Boot", "Court Shoe", "Rain Jacket"};
    private static final String SHOPPER_PASSWORD = System.getenv().getOrDefault("PERF_SHOPPER_PASSWORD", "perf-shopper-password");
    /** Shopper tokens must outlive the run: Keycloak issues them for 15 minutes. */
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(14);

    private final HttpClient http = client();
    /** A word unique to this run: product names carry it, so search can wait for exactly this run's products. */
    public final String runTag = "perf" + Long.toString(Instant.now().toEpochMilli(), 36);

    // ── catalogue ─────────────────────────────────────────────────────────────────────────────────────────────────────

    public List<Product> products(int count) {
        String staff = token(Stack.STAFF, Stack.STAFF);
        JsonNode category = send("POST", "/api/v1/admin/categories", staff,
                Map.of("slug", runTag, "name", "Performance " + runTag), 201);
        List<Product> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String sku = ("PERF-" + runTag + "-" + i).toUpperCase();
            String name = KINDS[i % KINDS.length] + " " + runTag + " " + i;
            JsonNode p = send("POST", "/api/v1/admin/products", staff, Map.of(
                    "sku", sku, "name", name, "categoryId", category.get("id").asText(),
                    "brand", BRANDS[i % BRANDS.length],
                    "price", Map.of("amount", "%d.90".formatted(20 + i % 80), "currency", "EUR"),
                    "status", "ACTIVE"), 201);
            send("PUT", "/api/v1/admin/stock/" + sku, staff, Map.of("available", 1_000_000), ANY_SUCCESS);
            out.add(new Product(p.get("id").asText(), sku, name));
        }
        return out;
    }

    /** Waits until search returns this run's products: indexing travels through Kafka. */
    public void awaitIndexed(int count) {
        Instant deadline = Instant.now().plusSeconds(90);
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> res = raw("GET", "/api/v1/search?limit=1&q=" + runTag, null, null);
            if (res.statusCode() == 200 && read(res).path("total").asInt() >= count) return;
            sleep(1_000);
        }
        throw new IllegalStateException("search did not index " + count + " products of " + runTag + " within 90 s");
    }

    // ── shoppers ──────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Shopper accounts perf-shopper-001… (created once, kept), each signed in, with an empty cart. */
    public List<Shopper> shoppers(int count) {
        if (Stack.WARM_UP.plus(Stack.RAMP).plus(Stack.STEADY).compareTo(TOKEN_LIFETIME) > 0) {
            throw new IllegalArgumentException("runs longer than " + TOKEN_LIFETIME.toMinutes() + " minutes would outlive the shoppers' tokens");
        }
        String admin = adminToken();
        List<Shopper> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String username = "perf-shopper-%03d".formatted(i);
            createUser(admin, username);
            String token = token(username, SHOPPER_PASSWORD);
            HttpResponse<String> cleared = raw("DELETE", "/api/v1/cart", token, null);
            if (cleared.statusCode() >= 300) throw new IllegalStateException("clear cart of " + username + ": " + cleared.statusCode());
            out.add(new Shopper(username, token));
        }
        return out;
    }

    private void createUser(String adminToken, String username) {
        Map<String, Object> user = Map.of(
                "username", username, "enabled", true, "emailVerified", true,
                "email", username + "@perf.shop.example", "firstName", "Perf", "lastName", username,
                "credentials", List.of(Map.of("type", "password", "value", SHOPPER_PASSWORD, "temporary", false)));
        HttpResponse<String> res = call(HttpRequest.newBuilder(URI.create(Stack.KEYCLOAK + "/admin/realms/" + Stack.REALM + "/users"))
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(user))).build());
        if (res.statusCode() != 201 && res.statusCode() != 409) {
            throw new IllegalStateException("create " + username + ": " + res.statusCode() + " " + res.body());
        }
    }

    private String adminToken() {
        return tokenFrom(Stack.KEYCLOAK + "/realms/master/protocol/openid-connect/token",
                Map.of("grant_type", "password", "client_id", "admin-cli",
                        "username", Stack.KEYCLOAK_ADMIN, "password", Stack.KEYCLOAK_ADMIN_PASSWORD));
    }

    public String token(String username, String password) {
        return tokenFrom(Stack.KEYCLOAK + "/realms/" + Stack.REALM + "/protocol/openid-connect/token",
                Map.of("grant_type", "password", "client_id", Stack.CLIENT_ID, "username", username, "password", password));
    }

    private String tokenFrom(String url, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpResponse<String> res = call(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
        if (res.statusCode() != 200) throw new IllegalStateException("token for " + form.get("username") + ": " + res.statusCode() + " " + res.body());
        return read(res).get("access_token").asText();
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    private static final int ANY_SUCCESS = 0;

    /** A call through the edge that must answer {@code expected} (or any 2xx); a 429 (rate limit) is waited out and retried. */
    private JsonNode send(String method, String path, String token, Object body, int expected) {
        for (int attempt = 0; attempt < 10; attempt++) {
            HttpResponse<String> res = raw(method, path, token, body);
            if (res.statusCode() == 429) {
                sleep(1_000L * Math.max(1, Integer.parseInt(res.headers().firstValue("Retry-After").orElse("1"))));
                continue;
            }
            if (expected == ANY_SUCCESS ? res.statusCode() / 100 != 2 : res.statusCode() != expected) throw new IllegalStateException(method + " " + path + ": " + res.statusCode() + " " + res.body());
            return res.body().isEmpty() ? JSON.nullNode() : read(res);
        }
        throw new IllegalStateException(method + " " + path + ": still rate limited");
    }

    private HttpResponse<String> raw(String method, String path, String token, Object body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(Stack.EDGE + path))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(write(body)));
        if (body != null) b.header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        return call(b.build());
    }

    private HttpResponse<String> call(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(request.method() + " " + request.uri() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static JsonNode read(HttpResponse<String> res) {
        try {
            return JSON.readTree(res.body());
        } catch (IOException e) {
            throw new IllegalStateException("not JSON from " + res.uri() + ": " + res.body(), e);
        }
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The local edge has a self-signed certificate; anywhere else (PERF_INSECURE_TLS=false) certificates are checked. */
    private static HttpClient client() {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        if (Stack.INSECURE_TLS) {
            try {
                SSLContext ssl = SSLContext.getInstance("TLS");
                ssl.init(null, new TrustManager[] {new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }}, new SecureRandom());
                SSLParameters params = new SSLParameters();
                params.setEndpointIdentificationAlgorithm(null);
                b.sslContext(ssl).sslParameters(params);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return b.build();
    }
}
