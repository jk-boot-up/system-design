package com.jkbootup.ecommerce.performance;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Placing orders (SPEC §2.2 N2: place order p99 ≤ 1.5 s). A signed-in shopper adds a product and places the order, which
 * runs the whole saga before answering: price the cart, reserve stock, authorise and capture payment. Every order uses
 * the approving test card; declines and timeouts are the end-to-end tests' business.
 */
public class CheckoutSimulation extends Simulation {

    private final Seed seed = new Seed();
    private final List<Seed.Product> products = seed.products(Stack.PRODUCTS);
    private final List<Seed.Shopper> shoppers = seed.shoppers(Stack.SHOPPERS);

    private ScenarioBuilder shopper(String phase) {
        return scenario(Phases.scenarioName(phase, "shopper checks out"))
            // Accounts in turn: with at least ten seconds of orders' worth of accounts, no two visitors share one.
            .feed(listFeeder(shoppers.stream().map(s -> Map.<String, Object>of("token", s.token())).toList()).circular())
            .feed(listFeeder(products.stream().map(p -> Map.<String, Object>of("sku", p.sku())).toList()).random())
            .exec(http(Phases.name(phase, "checkout: add to cart")).put("/api/v1/cart/items/#{sku}")
                    .header("Authorization", "Bearer #{token}")
                    .body(StringBody("{\"quantity\":1}")).asJson()
                    .check(status().is(200)))
            .pause(1, 2)
            .exec(http(Phases.name(phase, Budgets.PLACE_ORDER)).post("/api/v1/orders")
                    .header("Authorization", "Bearer #{token}")
                    .header("Idempotency-Key", session -> UUID.randomUUID().toString())
                    .body(StringBody("{\"paymentToken\":\"tok_ok\"}")).asJson()
                    .check(status().is(201), jmesPath("status").is("CONFIRMED")));
    }

    {
        setUp(Phases.warmUpThenMeasure(this::shopper, Stack.LOAD.checkout))
                .protocols(Http.PROTOCOL)
                .assertions(Budgets.assertions(Budgets.PLACE_ORDER));
    }
}
