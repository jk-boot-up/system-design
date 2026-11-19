package com.jkbootup.ecommerce.performance;

import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.global;

import io.gatling.javaapi.core.Assertion;
import java.util.ArrayList;
import java.util.List;

/**
 * The latency budgets of SPEC §2.2 N2 (p99), checked by every run. Each simulation names its measured request after the
 * budget it is held to; supporting requests (categories, cart reads) are measured but only held to the error budget.
 */
public final class Budgets {

    public static final String PRODUCT_PAGE = "product page";
    public static final String SEARCH = "search";
    public static final String ADD_TO_CART = "add to cart";
    public static final String PLACE_ORDER = "place order";

    /** Failed requests allowed across a run, in percent: well inside the 99.9–99.95 % availability of N1 per request. */
    private static final double MAX_FAILED_PERCENT = 0.1;

    private Budgets() {}

    public static int p99Millis(String request) {
        return switch (request) {
            case PRODUCT_PAGE -> 150;
            case SEARCH -> 300;
            case ADD_TO_CART -> 100;
            case PLACE_ORDER -> 1_500;
            default -> throw new IllegalArgumentException("no budget for " + request);
        };
    }

    /** p99 within budget for each named request, and the run's failures within the error budget. */
    public static Assertion[] assertions(String... requests) {
        List<Assertion> out = new ArrayList<>();
        for (String r : requests) out.add(details(r).responseTime().percentile(99.0).lte(p99Millis(r)));
        out.add(global().failedRequests().percent().lte(MAX_FAILED_PERCENT));
        return out.toArray(Assertion[]::new);
    }
}
