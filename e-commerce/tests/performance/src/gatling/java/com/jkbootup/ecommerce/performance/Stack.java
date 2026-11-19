package com.jkbootup.ecommerce.performance;

import java.time.Duration;

/**
 * Where the stack is and how hard to push it, from PERF_* environment variables. The defaults are the local compose stack
 * (deploy/compose) with the performance override, at a load a 4 GB laptop can carry; a production-like environment sets
 * {@code PERF_LOAD=target} for the rates of SPEC §2.2 N3.
 */
public final class Stack {

    /** Public entry point: the NGINX edge. */
    public static final String EDGE = env("PERF_EDGE", "https://localhost:8443");
    /** Keycloak, for test users (admin API) and their tokens (password grant, dev realm only). */
    public static final String KEYCLOAK = env("PERF_KEYCLOAK", "http://localhost:8180");
    public static final String REALM = env("PERF_REALM", "shop");
    public static final String CLIENT_ID = env("PERF_CLIENT_ID", "shop-web");
    public static final String KEYCLOAK_ADMIN = env("PERF_KEYCLOAK_ADMIN", "admin");
    public static final String KEYCLOAK_ADMIN_PASSWORD = env("PERF_KEYCLOAK_ADMIN_PASSWORD", "admin");
    /** The staff test user that creates the catalogue the simulations use. */
    public static final String STAFF = env("PERF_STAFF", "sam");
    /** Self-signed certificate on the local edge. */
    public static final boolean INSECURE_TLS = Boolean.parseBoolean(env("PERF_INSECURE_TLS", "true"));

    public static final Load LOAD = Load.valueOf(env("PERF_LOAD", "local").toUpperCase());

    /**
     * Shoppers for checkout, each with an account of their own: the gateway admits one order per user every five seconds,
     * and two visitors sharing an account would share a cart. Ten seconds' worth of orders keeps every account idle
     * between its turns.
     */
    public static final int SHOPPERS = Integer.parseInt(env("PERF_SHOPPERS", String.valueOf(Math.max(20, (int) Math.ceil(LOAD.checkout * 10)))));
    /** Products in the test catalogue. */
    public static final int PRODUCTS = Integer.parseInt(env("PERF_PRODUCTS", "50"));
    public static final Duration WARM_UP = Duration.ofSeconds(Long.parseLong(env("PERF_WARM_UP_SECONDS", "30")));
    public static final Duration RAMP = Duration.ofSeconds(Long.parseLong(env("PERF_RAMP_SECONDS", "20")));
    public static final Duration STEADY = Duration.ofSeconds(Long.parseLong(env("PERF_STEADY_SECONDS", "60")));

    /**
     * Arrival rates (new visitors per second) per simulation. LOCAL fits a laptop running the whole system in 4 GB.
     * TARGET is SPEC §2.2 N3 steady state: about 2,000 read requests a second across browse and search, and orders at a
     * tenth of the flash-sale peak (the peak itself is a separate, planned test).
     */
    public enum Load {
        LOCAL(8, 4, 6, 1),
        TARGET(300, 150, 200, 50);

        public final double browse;
        public final double search;
        public final double cart;
        public final double checkout;

        Load(double browse, double search, double cart, double checkout) {
            this.browse = browse;
            this.search = search;
            this.cart = cart;
            this.checkout = checkout;
        }
    }

    private Stack() {}

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
