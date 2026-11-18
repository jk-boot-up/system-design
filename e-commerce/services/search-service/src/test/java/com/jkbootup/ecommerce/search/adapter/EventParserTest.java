package com.jkbootup.ecommerce.search.adapter;

import com.jkbootup.ecommerce.search.adapter.messaging.EventParser;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventParserTest {

    final EventParser parser = new EventParser(JsonMapper.builder().build());

    @Test
    void productChangedBecomesADocument() {
        var doc = parser.product("""
                {"eventId":"e1","type":"catalog.ProductChanged","version":1,"occurredAt":"2026-11-18T10:00:00Z","aggregateId":"p1",
                 "payload":{"productId":"p1","sku":"SKU-1001","name":"Trail Runner 2","description":"","categorySlug":"running-shoes",
                 "brand":"Acme","price":{"amount":"89.90","currency":"EUR"},"attributes":{"size":"42"},"status":"ACTIVE",
                 "imageKeys":["products/p1/a.webp"],"catalogVersion":3}}""").orElseThrow();
        assertThat(doc.sku()).isEqualTo("SKU-1001");
        assertThat(doc.price()).isEqualByComparingTo("89.90");
        assertThat(doc.catalogVersion()).isEqualTo(3);
        assertThat(doc.image()).isEqualTo("products/p1/a.webp");
        assertThat(doc.attributes()).containsEntry("size", "42");
    }

    @Test
    void stockChangedBecomesAnUpdateAndOtherTypesAreSkipped() {
        var s = parser.stock("""
                {"type":"inventory.StockChanged","payload":{"sku":"SKU-1001","available":7,"inStock":true,"stockVersion":12}}""").orElseThrow();
        assertThat(s.available()).isEqualTo(7);
        assertThat(s.stockVersion()).isEqualTo(12);
        assertThat(parser.stock("{\"type\":\"inventory.Other\",\"payload\":{}}")).isEmpty();
        assertThat(parser.product("{\"type\":\"catalog.CategoryChanged\"}")).isEmpty();
    }

    @Test
    void malformedEventsAreMarkedForTheDeadLetterTopic() {
        assertThatThrownBy(() -> parser.product("not json")).isInstanceOf(EventParser.BadEventException.class);
        assertThatThrownBy(() -> parser.product("{\"type\":\"catalog.ProductChanged\",\"payload\":{\"sku\":\"X\"}}"))
                .isInstanceOf(EventParser.BadEventException.class);
        assertThatThrownBy(() -> parser.stock("{\"type\":\"inventory.StockChanged\",\"payload\":{\"sku\":\"X\"}}"))
                .isInstanceOf(EventParser.BadEventException.class);
    }
}
