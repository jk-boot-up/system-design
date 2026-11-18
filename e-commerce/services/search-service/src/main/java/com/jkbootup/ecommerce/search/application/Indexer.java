package com.jkbootup.ecommerce.search.application;

import com.jkbootup.ecommerce.search.application.port.SearchIndex;
import com.jkbootup.ecommerce.search.domain.ProductDocument;
import com.jkbootup.ecommerce.search.domain.StockUpdate;
import com.jkbootup.ecommerce.search.domain.WriteOutcome;

import java.util.concurrent.atomic.AtomicLong;

/** Keeps the index in step with the catalogue and inventory events. Stale (late or repeated) events are counted and dropped. */
public class Indexer {

    private final SearchIndex index;
    private final AtomicLong stale = new AtomicLong();

    public Indexer(SearchIndex index) {
        this.index = index;
    }

    public WriteOutcome productChanged(ProductDocument product) {
        return count(index.upsertProduct(product));
    }

    public WriteOutcome stockChanged(StockUpdate stock) {
        return count(index.updateStock(stock));
    }

    /** Events ignored because the index already held the same or a newer version. */
    public long staleEvents() {
        return stale.get();
    }

    private WriteOutcome count(WriteOutcome outcome) {
        if (outcome == WriteOutcome.STALE) stale.incrementAndGet();
        return outcome;
    }
}
