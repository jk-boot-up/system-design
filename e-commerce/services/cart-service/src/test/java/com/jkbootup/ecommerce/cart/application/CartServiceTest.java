package com.jkbootup.ecommerce.cart.application;

import com.jkbootup.ecommerce.cart.application.port.CartRepository;
import com.jkbootup.ecommerce.cart.application.port.PricingPort;
import com.jkbootup.ecommerce.cart.domain.Cart;
import com.jkbootup.ecommerce.cart.domain.CartOwner;
import com.jkbootup.ecommerce.cart.domain.Money;
import com.jkbootup.ecommerce.cart.domain.PricedCart;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CartServiceTest {

    private final Map<String, Map<String, Integer>> store = new HashMap<>();
    private final Set<String> seen = new HashSet<>();
    private boolean catalogUp = true;
    private final Map<String, PricingPort.ProductPrice> catalog = new HashMap<>(Map.of(
            "SKU-1", new PricingPort.ProductPrice("SKU-1", "Trail Runner", Money.of("89.90", "EUR"), true),
            "SKU-2", new PricingPort.ProductPrice("SKU-2", "Old Model", Money.of("10.00", "EUR"), false)));

    private final CartRepository repo = new CartRepository() {
        public Cart load(CartOwner o) { return new Cart(o, store.getOrDefault(o.key(), Map.of())); }

        public void setLine(CartOwner o, String sku, int q) {
            var m = store.computeIfAbsent(o.key(), k -> new HashMap<>());
            if (q == 0) m.remove(sku); else m.put(sku, q);
        }

        public void replace(Cart c) { store.put(c.owner().key(), new HashMap<>(c.items())); }

        public void delete(CartOwner o) { store.remove(o.key()); }
    };

    private final CartService service = new CartService(repo, skus -> catalogUp ? Optional.of(catalog) : Optional.empty(), seen::add);
    private final UUID customer = UUID.randomUUID();

    @Test
    void cartIsPricedFromTheCatalogue() {
        PricedCart c = service.setQuantity(CartOwner.customer(customer), "SKU-1", 2);
        assertThat(c.pricing()).isEqualTo(PricedCart.Pricing.CURRENT);
        assertThat(c.total()).contains(Money.of("179.80", "EUR"));
        assertThat(c.checkoutReady()).isTrue();
    }

    @Test
    void inactiveProductsBlockCheckout() {
        service.setQuantity(CartOwner.customer(customer), "SKU-2", 1);
        PricedCart c = service.view(CartOwner.customer(customer));
        assertThat(c.lines()).singleElement().extracting(PricedCart.Line::available).isEqualTo(false);
        assertThat(c.checkoutReady()).isFalse();
    }

    @Test
    void catalogueOutageShowsTheCartWithPricesPending() {
        service.setQuantity(CartOwner.customer(customer), "SKU-1", 2);
        catalogUp = false;
        PricedCart c = service.view(CartOwner.customer(customer));
        assertThat(c.pricing()).isEqualTo(PricedCart.Pricing.PENDING);
        assertThat(c.lines()).hasSize(1);
        assertThat(c.total()).isEmpty();
        assertThat(c.checkoutReady()).isFalse();
    }

    @Test
    void signInMergesTheGuestCart() {
        UUID guest = UUID.randomUUID();
        service.setQuantity(CartOwner.guest(guest), "SKU-1", 1);
        service.setQuantity(CartOwner.customer(customer), "SKU-1", 2);
        PricedCart merged = service.merge(customer, guest);
        assertThat(merged.lines()).singleElement().extracting(PricedCart.Line::quantity).isEqualTo(3);
        assertThat(store).doesNotContainKey("g:" + guest);
    }

    @Test
    void orderConfirmedClearsTheCartOnce() {
        service.setQuantity(CartOwner.customer(customer), "SKU-1", 1);
        service.orderConfirmed("e1", customer);
        assertThat(store).doesNotContainKey("c:" + customer);
        service.setQuantity(CartOwner.customer(customer), "SKU-1", 1);      // new cart after the order
        service.orderConfirmed("e1", customer);                              // duplicate delivery
        assertThat(store).containsKey("c:" + customer);
    }
}
