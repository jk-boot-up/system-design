package com.jkbootup.ecommerce.performance;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import java.util.List;
import java.util.Map;

/**
 * Searching (SPEC §2.2 N2: search p99 ≤ 300 ms). A visitor searches, then narrows the results by brand and sorts by price,
 * as the search page's facets do. Needs the compose "search" profile.
 */
public class SearchSimulation extends Simulation {

    private final Seed seed = new Seed();

    {
        seed.products(Stack.PRODUCTS);
        seed.awaitIndexed(Stack.PRODUCTS);
    }

    private final List<Map<String, Object>> queries = List.of(
            Map.of("q", "runner", "brand", "Acme"),
            Map.of("q", "trail runner", "brand", "Northwind"),
            Map.of("q", "boot", "brand", "Contoso"),
            Map.of("q", "jacket", "brand", "Tailspin"),
            Map.of("q", "court shoe", "brand", "Fabrikam"),
            Map.of("q", seed.runTag, "brand", "Acme"),
            Map.of("q", "racer " + seed.runTag, "brand", "Northwind"));

    private ScenarioBuilder visitor(String phase) {
        return scenario(Phases.scenarioName(phase, "visitor searches"))
            .feed(listFeeder(queries).random())
            .exec(http(Phases.name(phase, Budgets.SEARCH)).get("/api/v1/search").queryParam("q", "#{q}").queryParam("limit", "20")
                    .check(status().is(200), jmesPath("total").ofInt().gte(0)))
            .pause(1, 3)
            .exec(http(Phases.name(phase, Budgets.SEARCH)).get("/api/v1/search")
                    .queryParam("q", "#{q}").queryParam("brand", "#{brand}").queryParam("sort", "price_asc").queryParam("limit", "20")
                    .check(status().is(200)));
    }

    {
        setUp(Phases.warmUpThenMeasure(this::visitor, Stack.LOAD.search))
                .protocols(Http.PROTOCOL)
                .assertions(Budgets.assertions(Budgets.SEARCH));
    }
}
