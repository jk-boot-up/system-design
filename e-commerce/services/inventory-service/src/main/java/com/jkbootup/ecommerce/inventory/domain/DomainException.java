package com.jkbootup.ecommerce.inventory.domain;

/** A broken business rule. {@code code} is a stable, machine-readable identifier. */
public class DomainException extends RuntimeException {

    private final String code;

    public DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
