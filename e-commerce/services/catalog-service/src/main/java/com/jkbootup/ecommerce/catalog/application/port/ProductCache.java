package com.jkbootup.ecommerce.catalog.application.port;

import com.jkbootup.ecommerce.catalog.domain.Product;

import java.util.Optional;
import java.util.UUID;

/** A best-effort read cache. Implementations never throw: a cache failure reads as a miss. */
public interface ProductCache {

    Optional<Product> get(UUID id);

    void put(Product product);

    void evict(UUID id);
}
