package com.jkbootup.ecommerce.payment.domain;

/** A broken business rule; {@code code} is a stable, machine-readable identifier used in problem details. */
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
