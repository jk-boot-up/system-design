package com.jkbootup.ecommerce.cart.application.port;

import com.jkbootup.ecommerce.cart.domain.Cart;
import com.jkbootup.ecommerce.cart.domain.CartOwner;

public interface CartRepository {

    Cart load(CartOwner owner);

    /** Writes one line (quantity 0 deletes it) and refreshes the cart's expiry. */
    void setLine(CartOwner owner, String sku, int quantity);

    void replace(Cart cart);

    void delete(CartOwner owner);
}
