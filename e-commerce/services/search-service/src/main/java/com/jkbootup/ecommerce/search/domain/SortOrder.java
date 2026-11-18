package com.jkbootup.ecommerce.search.domain;

import java.util.Locale;

public enum SortOrder {
    RELEVANCE, PRICE_ASC, PRICE_DESC, NEWEST;

    public static SortOrder parse(String value) {
        if (value == null || value.isBlank()) return RELEVANCE;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DomainException("INVALID_SORT", "sort is one of relevance, price_asc, price_desc, newest");
        }
    }
}
