package com.jkbootup.ecommerce.search.domain;

import java.math.BigDecimal;
import java.util.Set;

/**
 * One search request. Pages are read with an opaque {@code cursor} (search_after), never with offsets, so deep pages
 * cost the same as the first and results do not shift when documents are added between pages.
 */
public record SearchQuery(String text, String category, Set<String> brands, BigDecimal minPrice, BigDecimal maxPrice,
                          Boolean inStock, SortOrder sort, int limit, String cursor) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    public SearchQuery {
        text = text == null || text.isBlank() ? null : text.trim();
        if (text != null && text.length() > 200) throw new DomainException("INVALID_QUERY", "q is at most 200 characters");
        brands = brands == null ? Set.of() : Set.copyOf(brands);
        if (brands.size() > 20) throw new DomainException("INVALID_QUERY", "At most 20 brands");
        if (minPrice != null && minPrice.signum() < 0) throw new DomainException("INVALID_QUERY", "minPrice cannot be negative");
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0)
            throw new DomainException("INVALID_QUERY", "minPrice is above maxPrice");
        sort = sort == null ? SortOrder.RELEVANCE : sort;
        if (limit == 0) limit = DEFAULT_LIMIT;
        if (limit < 1 || limit > MAX_LIMIT) throw new DomainException("INVALID_QUERY", "limit is 1 to " + MAX_LIMIT);
        if (sort == SortOrder.RELEVANCE && text == null) sort = SortOrder.NEWEST;     // no text, no relevance to rank by
    }
}
