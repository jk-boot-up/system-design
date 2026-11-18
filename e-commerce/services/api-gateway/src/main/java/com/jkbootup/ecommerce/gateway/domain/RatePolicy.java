package com.jkbootup.ecommerce.gateway.domain;

/** A token bucket: {@code ratePerSecond} tokens are added each second, up to {@code burst}. One request takes one token. */
public record RatePolicy(String name, double ratePerSecond, int burst) {

    public RatePolicy {
        if (ratePerSecond <= 0 || burst < 1) throw new IllegalArgumentException("rate and burst must be positive");
    }

    /** The same policy, scaled down: used when the shared buckets in Redis cannot be reached. */
    public RatePolicy scaled(double factor) {
        return new RatePolicy(name + "-local", ratePerSecond * factor, Math.max(1, (int) Math.floor(burst * factor)));
    }

    /** Whole seconds until one token is available again, given the tokens left (at least 1). */
    public long retryAfterSeconds(double tokensLeft) {
        return Math.max(1, (long) Math.ceil((1 - tokensLeft) / ratePerSecond));
    }
}
