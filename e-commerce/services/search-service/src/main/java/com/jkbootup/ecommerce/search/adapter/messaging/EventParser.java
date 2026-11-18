package com.jkbootup.ecommerce.search.adapter.messaging;

import com.jkbootup.ecommerce.search.domain.ProductDocument;
import com.jkbootup.ecommerce.search.domain.StockUpdate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Turns event envelopes into domain values. Events of other types are skipped; malformed ones raise {@link BadEventException}. */
public class EventParser {

    /** A message that can never be processed: it goes to the dead-letter topic at once instead of being retried. */
    public static class BadEventException extends RuntimeException {
        public BadEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final JsonMapper json;

    public EventParser(JsonMapper json) {
        this.json = json;
    }

    public Optional<ProductDocument> product(String message) {
        try {
            JsonNode e = json.readTree(message);
            if (!"catalog.ProductChanged".equals(e.path("type").asString())) return Optional.empty();
            JsonNode p = e.path("payload");
            JsonNode images = p.path("imageKeys");
            return Optional.of(new ProductDocument(text(p, "productId"), text(p, "sku"), text(p, "name"), p.path("description").asString(""),
                    text(p, "categorySlug"), text(p, "brand"), new BigDecimal(text(p.path("price"), "amount")),
                    text(p.path("price"), "currency"), json.convertValue(p.path("attributes"), MAP), text(p, "status"),
                    images.isArray() && !images.isEmpty() ? images.get(0).asString() : null,
                    Instant.parse(text(e, "occurredAt")), p.path("catalogVersion").asLong()));
        } catch (BadEventException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BadEventException("Unreadable ProductChanged: " + ex.getMessage(), ex);
        }
    }

    public Optional<StockUpdate> stock(String message) {
        try {
            JsonNode e = json.readTree(message);
            if (!"inventory.StockChanged".equals(e.path("type").asString())) return Optional.empty();
            JsonNode p = e.path("payload");
            if (!p.path("available").isNumber()) throw new BadEventException("StockChanged without available", null);
            return Optional.of(new StockUpdate(text(p, "sku"), p.path("available").asInt(), p.path("inStock").asBoolean(),
                    p.path("stockVersion").asLong(0)));
        } catch (BadEventException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BadEventException("Unreadable StockChanged: " + ex.getMessage(), ex);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) throw new BadEventException("Missing field " + field, null);
        return v.asString();
    }
}
