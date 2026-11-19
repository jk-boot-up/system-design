package com.jkbootup.ecommerce.performance;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import java.util.List;
import java.util.Map;

/**
 * Browsing (SPEC §2.2 N2: product page p99 ≤ 150 ms). A visitor loads the category tree, then opens three products,
 * reading each for a moment. Anonymous, as most browsing is.
 */
public class BrowseSimulation extends Simulation {

    // Seeded when the simulation is created, before any visitor arrives.
    private final List<Seed.Product> products = new Seed().products(Stack.PRODUCTS);

    private ScenarioBuilder visitor(String phase) {
        return scenario(Phases.scenarioName(phase, "visitor browses"))
            .exec(http(Phases.name(phase, "categories")).get("/api/v1/categories").check(status().is(200)))
            .pause(1, 3)
            .repeat(3).on(
                    feed(listFeeder(products.stream().map(p -> Map.<String, Object>of("id", p.id(), "sku", p.sku())).toList()).random())
                            .exec(http(Phases.name(phase, Budgets.PRODUCT_PAGE)).get("/api/v1/products/#{id}")
                                    .check(status().is(200), jmesPath("sku").isEL("#{sku}")))
                            .pause(1, 3));
    }

    {
        setUp(Phases.warmUpThenMeasure(this::visitor, Stack.LOAD.browse))
                .protocols(Http.PROTOCOL)
                .assertions(Budgets.assertions(Budgets.PRODUCT_PAGE));
    }
}
