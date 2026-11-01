package com.jkbootup.ecommerce.cart.domain;

import java.util.Objects;
import java.util.UUID;

/** Who a cart belongs to: a signed-in customer or an anonymous guest. */
public record CartOwner(Kind kind, UUID id) {

    public enum Kind { CUSTOMER, GUEST }

    public CartOwner {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(id);
    }

    public static CartOwner customer(UUID id) { return new CartOwner(Kind.CUSTOMER, id); }

    public static CartOwner guest(UUID id) { return new CartOwner(Kind.GUEST, id); }

    /** Storage key part: {@code c:<uuid>} or {@code g:<uuid>}. */
    public String key() {
        return (kind == Kind.CUSTOMER ? "c:" : "g:") + id;
    }
}
