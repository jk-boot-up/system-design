package com.jkbootup.ecommerce.cart.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** A cart: quantities per SKU. Prices are never stored; they are looked up when the cart is read. */
public final class Cart {

    public static final int MAX_QUANTITY = 99;
    public static final int MAX_LINES = 100;
    private static final Pattern SKU = Pattern.compile("[A-Z0-9][A-Z0-9-]{0,63}");

    private final CartOwner owner;
    private final Map<String, Integer> items;

    public Cart(CartOwner owner, Map<String, Integer> items) {
        this.owner = owner;
        this.items = new LinkedHashMap<>(items);
    }

    public static Cart empty(CartOwner owner) {
        return new Cart(owner, Map.of());
    }

    /** Sets the quantity of a SKU; 0 removes it. */
    public void setQuantity(String sku, int quantity) {
        if (!SKU.matcher(sku).matches()) throw new DomainException("INVALID_SKU", "Invalid SKU " + sku);
        if (quantity < 0 || quantity > MAX_QUANTITY)
            throw new DomainException("INVALID_QUANTITY", "Quantity must be between 0 and " + MAX_QUANTITY);
        if (quantity == 0) {
            items.remove(sku);
            return;
        }
        if (!items.containsKey(sku) && items.size() >= MAX_LINES)
            throw new DomainException("CART_FULL", "A cart holds at most " + MAX_LINES + " different products");
        items.put(sku, quantity);
    }

    /** Adds a guest cart into this one: quantities add up, capped at the maximum. */
    public void mergeFrom(Cart other) {
        other.items.forEach((sku, q) -> {
            int merged = Math.min(MAX_QUANTITY, items.getOrDefault(sku, 0) + q);
            if (items.containsKey(sku) || items.size() < MAX_LINES) items.put(sku, merged);
        });
    }

    public boolean isEmpty() { return items.isEmpty(); }

    public CartOwner owner() { return owner; }

    public Map<String, Integer> items() { return Collections.unmodifiableMap(items); }
}
