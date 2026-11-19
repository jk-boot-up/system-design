package com.jkbootup.ecommerce.performance;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;

import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import java.util.function.Function;

/**
 * Every simulation runs its scenario twice: a warm-up at half rate whose requests are named "warm-up: …" and held to no
 * budget, then the measured run (ramp, then steady). Freshly started JVMs compile their hot paths and caches fill during
 * the warm-up, as they have long done in production pods, so the budgets judge the system rather than its first minute.
 */
final class Phases {

    private static final String WARM_UP = "warm-up: ";

    private Phases() {}

    /** The request name in a phase: the bare name is the measured one that budgets apply to. */
    static String name(String phase, String request) {
        return phase + request;
    }

    /** {@code scenario} builds the scenario for a phase prefix ("" for the measured run). */
    static PopulationBuilder warmUpThenMeasure(Function<String, ScenarioBuilder> scenario, double rate) {
        return scenario.apply(WARM_UP).injectOpen(constantUsersPerSec(Math.max(0.5, rate / 2)).during(Stack.WARM_UP))
                .andThen(scenario.apply("").injectOpen(
                        rampUsersPerSec(Math.max(0.5, rate / 2)).to(rate).during(Stack.RAMP),
                        constantUsersPerSec(rate).during(Stack.STEADY)));
    }

    /** Scenario names must be unique within a simulation. */
    static String scenarioName(String phase, String name) {
        return phase.isEmpty() ? name : name + " (warm-up)";
    }
}
