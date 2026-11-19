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
 * Adding to the cart (SPEC §2.2 N2: add to cart p99 ≤ 100 ms). A guest adds two products, one of them twice, and looks at
 * the cart. Each add prices the whole cart from the catalogue (cart-service's price cache absorbs most of it).
 */
public class CartSimulation extends Simulation {

    private final List<Seed.Product> products = new Seed().products(Stack.PRODUCTS);

    private ScenarioBuilder guest(String phase) {
        return scenario(Phases.scenarioName(phase, "guest fills a cart"))
            .exec(session -> session.set("guestId", UUID.randomUUID().toString()))
            .repeat(2).on(
                    feed(listFeeder(products.stream().map(p -> Map.<String, Object>of("sku", p.sku())).toList()).random())
                            .exec(http(Phases.name(phase, Budgets.ADD_TO_CART)).put("/api/v1/cart/items/#{sku}")
                                    .header("X-Guest-Id", "#{guestId}")
                                    .body(StringBody("{\"quantity\":1}")).asJson()
                                    .check(status().is(200)))
                            .pause(1, 2))
            .exec(http(Phases.name(phase, Budgets.ADD_TO_CART)).put("/api/v1/cart/items/#{sku}")
                    .header("X-Guest-Id", "#{guestId}")
                    .body(StringBody("{\"quantity\":2}")).asJson()
                    .check(status().is(200), jmesPath("lines[?sku=='#{sku}'].quantity | [0]").ofInt().is(2)))
            .exec(http(Phases.name(phase, "view cart")).get("/api/v1/cart").header("X-Guest-Id", "#{guestId}").check(status().is(200)));
    }

    {
        setUp(Phases.warmUpThenMeasure(this::guest, Stack.LOAD.cart))
                .protocols(Http.PROTOCOL)
                .assertions(Budgets.assertions(Budgets.ADD_TO_CART));
    }
}
