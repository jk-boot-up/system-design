package com.jkbootup.ecommerce.cart.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CartTest {

    private final Cart cart = Cart.empty(CartOwner.customer(UUID.randomUUID()));

    @Test
    void setAndRemoveLines() {
        cart.setQuantity("SKU-1", 2);
        cart.setQuantity("SKU-1", 3);
        cart.setQuantity("SKU-2", 1);
        cart.setQuantity("SKU-2", 0);
        assertThat(cart.items()).containsExactly(Map.entry("SKU-1", 3));
    }

    @Test
    void rejectsBadInput() {
        assertThatThrownBy(() -> cart.setQuantity("sku 1", 1)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> cart.setQuantity("SKU-1", 100)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> cart.setQuantity("SKU-1", -1)).isInstanceOf(DomainException.class);
    }

    @Test
    void mergeAddsQuantitiesUpToTheCap() {
        cart.setQuantity("SKU-1", 60);
        Cart guest = Cart.empty(CartOwner.guest(UUID.randomUUID()));
        guest.setQuantity("SKU-1", 50);
        guest.setQuantity("SKU-2", 1);
        cart.mergeFrom(guest);
        assertThat(cart.items()).containsEntry("SKU-1", 99).containsEntry("SKU-2", 1);
    }

    @Test
    void ownerKeysDistinguishCustomersAndGuests() {
        UUID id = UUID.randomUUID();
        assertThat(CartOwner.customer(id).key()).isEqualTo("c:" + id);
        assertThat(CartOwner.guest(id).key()).isEqualTo("g:" + id);
    }
}
