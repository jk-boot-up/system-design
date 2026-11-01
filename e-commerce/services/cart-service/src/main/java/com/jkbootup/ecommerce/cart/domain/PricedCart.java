package com.jkbootup.ecommerce.cart.domain;

import java.util.List;
import java.util.Optional;

/**
 * A cart with current prices. When the catalogue could not be reached, {@code pricing} is PENDING and lines carry no
 * price: the customer still sees their cart, but checkout must wait.
 */
public record PricedCart(CartOwner owner, List<Line> lines, Optional<Money> total, Pricing pricing) {

    public enum Pricing { CURRENT, PENDING }

    public record Line(String sku, String name, int quantity, Optional<Money> unitPrice, boolean available) {
        public Optional<Money> lineTotal() {
            return unitPrice.map(p -> p.times(quantity));
        }
    }

    public boolean checkoutReady() {
        return pricing == Pricing.CURRENT && !lines.isEmpty() && lines.stream().allMatch(Line::available);
    }
}
