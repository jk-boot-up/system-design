package com.jkbootup.ecommerce.order.adapter.client;

import com.jkbootup.ecommerce.order.application.port.InventoryPort;
import com.jkbootup.ecommerce.order.domain.OrderLine;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** inventory-service client. Every call is idempotent per order id, so all of them may be retried. */
public class InventoryHttpClient implements InventoryPort {

    private final RestClient http;
    private final ResilientCall resilient = new ResilientCall("inventory");
    private final JsonMapper json;

    public InventoryHttpClient(RestClient http, JsonMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public Reservation reserve(UUID orderId, List<OrderLine> lines) {
        var body = Map.of("orderId", orderId, "lines",
                lines.stream().map(l -> Map.of("sku", l.sku(), "quantity", l.quantity())).toList());
        return resilient.run(true, () -> {
            try {
                http.post().uri("/internal/reservations").contentType(MediaType.APPLICATION_JSON).body(body)
                        .retrieve().toBodilessEntity();
                return (Reservation) new Reservation.Reserved();
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.CONFLICT) {
                    JsonNode problem = json.readTree(e.getResponseBodyAsString());
                    if ("OUT_OF_STOCK".equals(problem.path("code").asString()))
                        return new Reservation.OutOfStock(problem.path("sku").asString());
                }
                return new Reservation.Unavailable("inventory rejected the request: " + e.getStatusCode());
            }
        }, f -> new Reservation.Unavailable(f.name()));
    }

    @Override
    public Step commit(UUID orderId) {
        return command("/internal/reservations/{id}/commit", orderId);
    }

    @Override
    public Step release(UUID orderId) {
        return command("/internal/reservations/{id}/release", orderId);
    }

    @Override
    public Step returnStock(UUID orderId) {
        return command("/internal/reservations/{id}/return", orderId);
    }

    private Step command(String path, UUID orderId) {
        return resilient.run(true, () -> {
            try {
                http.post().uri(path, orderId).retrieve().toBodilessEntity();
                return (Step) new Step.Done();
            } catch (HttpClientErrorException e) {
                String code = json.readTree(e.getResponseBodyAsString()).path("code").asString("HTTP_" + e.getStatusCode().value());
                return new Step.Rejected(code);
            }
        }, f -> new Step.Unavailable(f.name()));
    }
}
