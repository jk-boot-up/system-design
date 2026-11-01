package com.jkbootup.ecommerce.inventory.domain;

/** One line to reserve. */
public record OrderLine(Sku sku, Quantity quantity) {}
