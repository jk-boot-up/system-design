plugins {
    java
    id("io.gatling.gradle") version "3.16.0"
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

// Settings reach the simulations as PERF_* environment variables (Stack.java). Jackson comes with Gatling.
