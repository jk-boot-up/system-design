package com.jkbootup.ecommerce.order.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;

import java.util.Map;

/**
 * Carries a trace across the transactional outbox: the writer stores the W3C {@code traceparent} of the request that
 * produced the event, and the relay sends the event inside that context, so the Kafka producer span (and every consumer
 * after it) joins the original trace instead of starting one of its own.
 */
public final class TraceContext {

    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override public Iterable<String> keys(Map<String, String> carrier) { return carrier.keySet(); }
        @Override public String get(Map<String, String> carrier, String key) { return carrier == null ? null : carrier.get(key); }
    };

    private TraceContext() {}

    /** The current span as a W3C traceparent, or null outside a trace. */
    public static String currentTraceparent() {
        SpanContext c = Span.current().getSpanContext();
        if (!c.isValid()) return null;
        return "00-" + c.getTraceId() + "-" + c.getSpanId() + "-" + c.getTraceFlags().asHex();
    }

    /** The context a stored traceparent describes; the root context when there is none or it is malformed. */
    public static Context fromTraceparent(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) return Context.root();
        return W3CTraceContextPropagator.getInstance().extract(Context.root(), Map.of("traceparent", traceparent), GETTER);
    }
}
