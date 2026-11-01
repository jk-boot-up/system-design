package com.jkbootup.ecommerce.cart.application;

import com.jkbootup.ecommerce.cart.application.port.CartRepository;
import com.jkbootup.ecommerce.cart.application.port.PricingPort;
import com.jkbootup.ecommerce.cart.application.port.PricingPort.ProductPrice;
import com.jkbootup.ecommerce.cart.application.port.ProcessedEvents;
import com.jkbootup.ecommerce.cart.domain.Cart;
import com.jkbootup.ecommerce.cart.domain.CartOwner;
import com.jkbootup.ecommerce.cart.domain.Money;
import com.jkbootup.ecommerce.cart.domain.PricedCart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Cart use cases. Writes go straight to Redis; reads re-price every line from the catalogue. */
public class CartService {

    private final CartRepository carts;
    private final PricingPort pricing;
    private final ProcessedEvents processed;

    public CartService(CartRepository carts, PricingPort pricing, ProcessedEvents processed) {
        this.carts = carts;
        this.pricing = pricing;
        this.processed = processed;
    }

    public PricedCart view(CartOwner owner) {
        return price(carts.load(owner));
    }

    public PricedCart setQuantity(CartOwner owner, String sku, int quantity) {
        Cart cart = carts.load(owner);
        cart.setQuantity(sku, quantity);               // validates; throws before anything is written
        carts.setLine(owner, sku, quantity);
        return price(cart);
    }

    public void clear(CartOwner owner) {
        carts.delete(owner);
    }

    /** On sign-in: the guest's cart is added to the customer's, then removed. */
    public PricedCart merge(UUID customerId, UUID guestId) {
        CartOwner customer = CartOwner.customer(customerId);
        Cart target = carts.load(customer);
        Cart guest = carts.load(CartOwner.guest(guestId));
        if (!guest.isEmpty()) {
            target.mergeFrom(guest);
            carts.replace(target);
            carts.delete(guest.owner());
        }
        return price(target);
    }

    /** For order-service: the cart priced right now. Prices must be current; a stale price never reaches an order. */
    public PricedCart checkoutSnapshot(UUID customerId) {
        return view(CartOwner.customer(customerId));
    }

    /** OrderConfirmed: the order was placed from this cart, so empty it. Duplicate events do nothing. */
    public void orderConfirmed(String eventId, UUID customerId) {
        if (processed.firstTime(eventId)) carts.delete(CartOwner.customer(customerId));
    }

    private PricedCart price(Cart cart) {
        if (cart.isEmpty()) return new PricedCart(cart.owner(), List.of(), Optional.empty(), PricedCart.Pricing.CURRENT);
        Optional<Map<String, ProductPrice>> prices = pricing.prices(cart.items().keySet());
        List<PricedCart.Line> lines = new ArrayList<>();
        if (prices.isEmpty()) {                          // degraded: show the cart without prices
            cart.items().forEach((sku, q) -> lines.add(new PricedCart.Line(sku, sku, q, Optional.empty(), true)));
            return new PricedCart(cart.owner(), lines, Optional.empty(), PricedCart.Pricing.PENDING);
        }
        Money total = null;
        for (var e : cart.items().entrySet()) {
            ProductPrice p = prices.get().get(e.getKey());
            boolean available = p != null && p.active();
            lines.add(new PricedCart.Line(e.getKey(), p == null ? e.getKey() : p.name(), e.getValue(),
                    Optional.ofNullable(p).map(ProductPrice::price), available));
            if (available) total = total == null ? p.price().times(e.getValue()) : total.plus(p.price().times(e.getValue()));
        }
        return new PricedCart(cart.owner(), lines, Optional.ofNullable(total), PricedCart.Pricing.CURRENT);
    }
}
