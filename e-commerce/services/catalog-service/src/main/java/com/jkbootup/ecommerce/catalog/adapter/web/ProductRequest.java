package com.jkbootup.ecommerce.catalog.adapter.web;

import com.jkbootup.ecommerce.catalog.domain.Money;
import com.jkbootup.ecommerce.catalog.domain.ProductDetails;
import com.jkbootup.ecommerce.catalog.domain.ProductStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

/** ProductCreate and ProductUpdate. The SKU is only read on create; it never changes afterwards. */
public record ProductRequest(@Size(max = 40) String sku, @NotBlank @Size(max = 200) String name, @Size(max = 10_000) String description,
                             @NotNull UUID categoryId, @NotBlank @Size(max = 100) String brand, @NotNull @Valid Price price,
                             Map<String, Object> attributes, ProductStatus status) {

    public record Price(@NotNull @Pattern(regexp = "\\d{1,15}(\\.\\d{1,2})?") String amount,
                        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {}

    ProductDetails details() {
        return new ProductDetails(name, description, categoryId, brand, Money.of(price.amount(), price.currency()), attributes, status);
    }
}
