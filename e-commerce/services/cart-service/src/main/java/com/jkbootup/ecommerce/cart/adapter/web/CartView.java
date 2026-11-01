package com.jkbootup.ecommerce.cart.adapter.web;

import com.jkbootup.ecommerce.cart.domain.Money;
import com.jkbootup.ecommerce.cart.domain.PricedCart;

import java.util.List;

/** JSON shape of a cart (the same for the public API and the internal checkout snapshot). */
public record CartView(String owner, List<Line> lines, MoneyView total, String pricing, boolean checkoutReady) {

    public record MoneyView(String amount, String currency) {
        static MoneyView of(Money m) {
            return new MoneyView(m.amount().toPlainString(), m.currency().getCurrencyCode());
        }
    }

    public record Line(String sku, String name, int quantity, MoneyView unitPrice, MoneyView lineTotal, boolean available) {}

    static CartView of(PricedCart c) {
        return new CartView(c.owner().key(),
                c.lines().stream().map(l -> new Line(l.sku(), l.name(), l.quantity(), l.unitPrice().map(MoneyView::of).orElse(null),
                        l.lineTotal().map(MoneyView::of).orElse(null), l.available())).toList(),
                c.total().map(MoneyView::of).orElse(null), c.pricing().name(), c.checkoutReady());
    }
}
