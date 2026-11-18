package com.jkbootup.ecommerce.search.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchQueryTest {

    @Test
    void defaultsAreApplied() {
        SearchQuery q = new SearchQuery("  trail  ", null, null, null, null, null, null, 0, null);
        assertThat(q.text()).isEqualTo("trail");
        assertThat(q.limit()).isEqualTo(20);
        assertThat(q.sort()).isEqualTo(SortOrder.RELEVANCE);
        assertThat(q.brands()).isEmpty();
    }

    @Test
    void withoutTextRelevanceMeansNewestFirst() {
        assertThat(new SearchQuery(" ", null, null, null, null, null, SortOrder.RELEVANCE, 10, null).sort()).isEqualTo(SortOrder.NEWEST);
    }

    @Test
    void invalidRequestsAreRejected() {
        assertThatThrownBy(() -> new SearchQuery(null, null, null, null, null, null, null, 101, null)).hasMessageContaining("limit");
        assertThatThrownBy(() -> new SearchQuery(null, null, null, new BigDecimal("50"), new BigDecimal("10"), null, null, 10, null))
                .hasMessageContaining("above maxPrice");
        assertThatThrownBy(() -> new SearchQuery("x".repeat(201), null, null, null, null, null, null, 10, null)).hasMessageContaining("200");
        assertThatThrownBy(() -> SortOrder.parse("cheapest")).isInstanceOf(DomainException.class);
        assertThat(SortOrder.parse("price_asc")).isEqualTo(SortOrder.PRICE_ASC);
    }
}
