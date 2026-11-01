package com.jkbootup.ecommerce.cart.application.port;

import com.jkbootup.ecommerce.cart.domain.Money;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** Current product names, prices and availability from catalog-service. Empty when it cannot be reached. */
public interface PricingPort {

    record ProductPrice(String sku, String name, Money price, boolean active) {}

    Optional<Map<String, ProductPrice>> prices(Collection<String> skus);
}
