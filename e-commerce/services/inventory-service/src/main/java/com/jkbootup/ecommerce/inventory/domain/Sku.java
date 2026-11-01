package com.jkbootup.ecommerce.inventory.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stock-keeping unit: upper-case letters, digits and dashes, 1 to 64 characters. */
public record Sku(String value) implements Comparable<Sku> {

    private static final Pattern FORMAT = Pattern.compile("[A-Z0-9][A-Z0-9-]{0,63}");

    public Sku {
        Objects.requireNonNull(value, "sku");
        if (!FORMAT.matcher(value).matches()) throw new DomainException("INVALID_SKU", "Invalid SKU: " + value);
    }

    public static Sku of(String value) {
        return new Sku(value);
    }

    @Override
    public int compareTo(Sku other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
