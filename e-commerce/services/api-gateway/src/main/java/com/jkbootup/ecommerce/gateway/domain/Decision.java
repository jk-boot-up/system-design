package com.jkbootup.ecommerce.gateway.domain;

/** The answer for one request: allowed or not, tokens left, seconds to wait, and whether the shared store answered. */
public record Decision(boolean allowed, long remaining, long retryAfterSeconds, boolean shared) {
}
