package com.jkbootup.ecommerce.search.adapter.messaging;

import com.jkbootup.ecommerce.search.application.Indexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * Consumes the catalogue and inventory topics. Writes are version-guarded, so no dedupe table is needed: a repeated or
 * late event is simply a no-op. When OpenSearch is down the error handler retries the same record with back-off,
 * which pauses the partition rather than skipping data.
 */
public class IndexingListeners {

    private static final Logger log = LoggerFactory.getLogger(IndexingListeners.class);

    private final Indexer indexer;
    private final EventParser parser;

    public IndexingListeners(Indexer indexer, EventParser parser) {
        this.indexer = indexer;
        this.parser = parser;
    }

    @KafkaListener(id = "search.catalog", topics = "catalog.events", groupId = "search-service")
    public void onCatalogEvent(String message) {
        parser.product(message).ifPresent(p -> log.debug("{} v{}: {}", p.sku(), p.catalogVersion(), indexer.productChanged(p)));
    }

    @KafkaListener(id = "search.inventory", topics = "inventory.events", groupId = "search-service")
    public void onInventoryEvent(String message) {
        parser.stock(message).ifPresent(s -> log.debug("{} stock v{}: {}", s.sku(), s.stockVersion(), indexer.stockChanged(s)));
    }
}
