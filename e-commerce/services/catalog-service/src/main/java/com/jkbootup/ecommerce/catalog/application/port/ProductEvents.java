package com.jkbootup.ecommerce.catalog.application.port;

import com.jkbootup.ecommerce.catalog.domain.Category;
import com.jkbootup.ecommerce.catalog.domain.Product;

/** Publishes catalog.ProductChanged. Must be called inside the transaction that changed the product (outbox). */
public interface ProductEvents {

    void productChanged(Product product, Category category);
}
