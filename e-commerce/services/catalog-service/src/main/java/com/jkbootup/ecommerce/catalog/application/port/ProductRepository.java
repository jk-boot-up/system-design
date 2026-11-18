package com.jkbootup.ecommerce.catalog.application.port;

import com.jkbootup.ecommerce.catalog.domain.Product;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository {

    Optional<Product> findById(UUID id);

    List<Product> findBySkus(Collection<String> skus);

    /** @throws com.jkbootup.ecommerce.catalog.domain.DomainException SKU_TAKEN when the SKU exists already */
    void insert(Product product);

    /** Writes {@code product} only if the stored version is still {@code expectedVersion}; false when it is not. */
    boolean update(Product product, long expectedVersion);
}
