package com.jkbootup.ecommerce.catalog.adapter.persistence;

import com.jkbootup.ecommerce.catalog.application.port.ProductEvents;
import com.jkbootup.ecommerce.catalog.domain.Category;
import com.jkbootup.ecommerce.catalog.domain.Ids;
import com.jkbootup.ecommerce.catalog.domain.Product;
import com.jkbootup.ecommerce.catalog.domain.ProductDetails;
import com.jkbootup.ecommerce.catalog.domain.ProductImage;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional outbox for catalog.ProductChanged. The event carries the whole product (event-carried state transfer)
 * and its {@code catalogVersion}, which consumers use to ignore events older than what they already hold.
 */
public class OutboxProductEvents implements ProductEvents {

    public static final String TOPIC = "catalog.events";
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxProductEvents(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void productChanged(Product p, Category category) {
        UUID eventId = Ids.newId();
        ProductDetails d = p.details();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", p.id().toString());
        payload.put("sku", p.sku());
        payload.put("name", d.name());
        payload.put("description", d.description());
        payload.put("categorySlug", category.slug());
        payload.put("brand", d.brand());
        payload.put("price", Map.of("amount", d.price().amount().toPlainString(), "currency", d.price().currency().getCurrencyCode()));
        payload.put("attributes", d.attributes());
        payload.put("status", d.status().name());
        payload.put("imageKeys", p.images().stream().map(ProductImage::objectKey).toList());
        payload.put("catalogVersion", p.version());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("type", "catalog.ProductChanged");
        envelope.put("version", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("aggregateId", p.id().toString());
        envelope.put("payload", payload);
        jdbc.sql("""
                        insert into outbox (id, aggregate_type, aggregate_id, event_type, topic, payload)
                        values (:id, 'Product', :agg, 'catalog.ProductChanged', :topic, cast(:payload as jsonb))""")
                .param("id", eventId).param("agg", p.id().toString()).param("topic", TOPIC)
                .param("payload", json.writeValueAsString(envelope)).update();
    }
}
