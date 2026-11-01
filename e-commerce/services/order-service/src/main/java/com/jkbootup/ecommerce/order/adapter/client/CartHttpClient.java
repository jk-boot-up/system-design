package com.jkbootup.ecommerce.order.adapter.client;

import com.jkbootup.ecommerce.order.application.port.CartPort;
import com.jkbootup.ecommerce.order.domain.Money;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** cart-service client: reads the customer's cart with current prices. Read-only, so retries are safe. */
public class CartHttpClient implements CartPort {

    private final RestClient http;
    private final ResilientCall resilient = new ResilientCall("cart");

    public CartHttpClient(RestClient http) {
        this.http = http;
    }

    @Override
    public Snapshot checkoutSnapshot(UUID customerId) {
        return resilient.run(true, () -> {
            JsonNode body = http.get().uri("/internal/carts/{id}/checkout-snapshot", customerId).retrieve().body(JsonNode.class);
            List<Line> lines = new ArrayList<>();
            for (JsonNode l : body.path("lines")) {
                JsonNode price = l.path("unitPrice");
                lines.add(new Line(l.path("sku").asString(), l.path("name").asString(), l.path("quantity").asInt(),
                        Money.of(price.path("amount").asString(), price.path("currency").asString()), l.path("available").asBoolean()));
            }
            return (Snapshot) new Snapshot.Ready(lines);
        }, f -> new Snapshot.Unavailable(f.name()));
    }
}
