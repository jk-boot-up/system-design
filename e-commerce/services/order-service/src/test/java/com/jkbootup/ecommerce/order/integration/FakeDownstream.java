package com.jkbootup.ecommerce.order.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A real HTTP server standing in for cart, inventory and payment, so the order-service clients, timeouts and error
 * mapping are exercised over the network. Behaviour is switched per test.
 */
final class FakeDownstream implements AutoCloseable {

    volatile boolean outOfStock;
    volatile boolean declinePayment;
    final List<String> calls = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    FakeDownstream() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        calls.add(ex.getRequestMethod() + " " + path.replaceAll("[0-9a-f-]{36}", "{id}"));
        if (path.endsWith("/checkout-snapshot")) {
            reply(ex, 200, """
                    {"lines":[{"sku":"SKU-1001","name":"Trail Runner","quantity":2,
                      "unitPrice":{"amount":"89.90","currency":"EUR"},"available":true}],
                     "total":{"amount":"179.80","currency":"EUR"}}""");
        } else if (path.equals("/internal/reservations")) {
            if (outOfStock) reply(ex, 409, "{\"code\":\"OUT_OF_STOCK\",\"sku\":\"SKU-1001\"}");
            else reply(ex, 200, "{\"status\":\"RESERVED\"}");
        } else if (path.equals("/internal/payments/authorize")) {
            if (declinePayment) reply(ex, 402, "{\"code\":\"PAYMENT_DECLINED\"}");
            else reply(ex, 200, "{\"status\":\"AUTHORIZED\"}");
        } else {
            reply(ex, 200, "{\"status\":\"OK\"}");
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", status >= 400 ? "application/problem+json" : "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
