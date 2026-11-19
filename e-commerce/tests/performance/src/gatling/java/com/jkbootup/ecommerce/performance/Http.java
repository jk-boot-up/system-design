package com.jkbootup.ecommerce.performance;

import static io.gatling.javaapi.http.HttpDsl.http;

import io.gatling.javaapi.http.HttpProtocolBuilder;

/** The HTTP protocol every simulation uses: the public edge, as a browser's API calls reach it. */
final class Http {

    static final HttpProtocolBuilder PROTOCOL = http
            .baseUrl(Stack.EDGE)
            .acceptHeader("application/json")
            .acceptEncodingHeader("gzip, br")
            .userAgentHeader("e-commerce-performance (Gatling)")
            .disableCaching();   // every visitor is new: no conditional requests

    private Http() {}
}
