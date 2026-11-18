package com.jkbootup.ecommerce.search.application;

import com.jkbootup.ecommerce.search.application.port.SearchIndex;
import com.jkbootup.ecommerce.search.domain.ProductDocument;
import com.jkbootup.ecommerce.search.domain.SearchQuery;
import com.jkbootup.ecommerce.search.domain.SearchResult;
import com.jkbootup.ecommerce.search.domain.StockUpdate;
import com.jkbootup.ecommerce.search.domain.WriteOutcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IndexerTest {

    /** Keeps the highest version per SKU, like the scripted upsert in OpenSearch. */
    static class VersionedIndex implements SearchIndex {
        final Map<String, ProductDocument> products = new HashMap<>();
        final Map<String, StockUpdate> stock = new HashMap<>();

        @Override
        public WriteOutcome upsertProduct(ProductDocument p) {
            ProductDocument old = products.get(p.sku());
            if (old != null && old.catalogVersion() >= p.catalogVersion()) return WriteOutcome.STALE;
            products.put(p.sku(), p);
            return WriteOutcome.APPLIED;
        }

        @Override
        public WriteOutcome updateStock(StockUpdate s) {
            StockUpdate old = stock.get(s.sku());
            if (old != null && old.stockVersion() >= s.stockVersion()) return WriteOutcome.STALE;
            stock.put(s.sku(), s);
            return WriteOutcome.APPLIED;
        }

        @Override
        public SearchResult search(SearchQuery query) {
            throw new UnsupportedOperationException();
        }
    }

    static ProductDocument product(String price, long version) {
        return new ProductDocument("p1", "SKU-1001", "Trail Runner 2", "", "running-shoes", "Acme", new BigDecimal(price), "EUR",
                Map.of(), "ACTIVE", null, Instant.EPOCH, version);
    }

    @Test
    void lateAndRepeatedEventsAreCountedAndDropped() {
        VersionedIndex index = new VersionedIndex();
        Indexer indexer = new Indexer(index);
        assertThat(indexer.productChanged(product("79.90", 2))).isEqualTo(WriteOutcome.APPLIED);
        assertThat(indexer.productChanged(product("89.90", 1))).isEqualTo(WriteOutcome.STALE);
        assertThat(indexer.productChanged(product("79.90", 2))).isEqualTo(WriteOutcome.STALE);
        assertThat(indexer.stockChanged(new StockUpdate("SKU-1001", 3, true, 9))).isEqualTo(WriteOutcome.APPLIED);
        assertThat(indexer.stockChanged(new StockUpdate("SKU-1001", 5, true, 8))).isEqualTo(WriteOutcome.STALE);
        assertThat(index.products.get("SKU-1001").price()).isEqualByComparingTo("79.90");
        assertThat(index.stock.get("SKU-1001").available()).isEqualTo(3);
        assertThat(indexer.staleEvents()).isEqualTo(3);
    }
}
