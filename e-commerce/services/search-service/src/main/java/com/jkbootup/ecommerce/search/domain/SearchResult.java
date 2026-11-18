package com.jkbootup.ecommerce.search.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record SearchResult(List<Hit> items, Map<String, List<FacetValue>> facets, long total, String nextCursor) {

    public record Hit(String productId, String sku, String name, String brand, String category, BigDecimal price, String currency,
                      boolean inStock, String image) {}

    public record FacetValue(String value, long count) {}
}
