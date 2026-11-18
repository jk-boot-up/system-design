package com.jkbootup.ecommerce.catalog.application.port;

import com.jkbootup.ecommerce.catalog.domain.Category;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository {

    List<Category> findAll();

    Optional<Category> findById(UUID id);

    /** @throws com.jkbootup.ecommerce.catalog.domain.DomainException SLUG_TAKEN when the slug exists already */
    void insert(Category category);
}
