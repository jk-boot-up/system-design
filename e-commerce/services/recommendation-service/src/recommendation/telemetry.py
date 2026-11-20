"""Tracing (OpenTelemetry, OTLP over HTTP) and trace ids in log lines.

Spans are exported when OTEL_EXPORTER_OTLP_TRACES_ENDPOINT or OTEL_EXPORTER_OTLP_ENDPOINT is set (compose, Kubernetes);
otherwise none are recorded, but the trace context is still read. Every span is kept here: the collector samples whole
traces (tail sampling)."""

from __future__ import annotations

import logging
import os

from opentelemetry import propagate, trace
from opentelemetry.propagators.textmap import Getter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator

SERVICE = "recommendation-service"
tracer = trace.get_tracer(SERVICE)


def setup() -> TracerProvider | None:
    propagate.set_global_textmap(TraceContextTextMapPropagator())
    if not (os.getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT") or os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")):
        return None
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

    provider = TracerProvider(resource=Resource.create({"service.name": SERVICE}))
    provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter()))
    trace.set_tracer_provider(provider)
    return provider


class KafkaHeaders(Getter):
    """Reads trace context from a Kafka record's headers: a list of (name, bytes) pairs."""

    def get(self, carrier, key):
        values = [v.decode("latin-1") for k, v in carrier or () if k == key and v is not None]
        return values or None

    def keys(self, carrier):
        return [k for k, _ in carrier or ()]


def context_of(headers):
    """The trace context the producer wrote with the record (order-service's outbox sets traceparent)."""
    return propagate.extract(headers, getter=KafkaHeaders())


class TraceIds(logging.Filter):
    """Adds traceId and spanId (empty outside a span) to every log record, for the JSON log format."""

    def filter(self, record: logging.LogRecord) -> bool:
        ctx = trace.get_current_span().get_span_context()
        record.traceId = format(ctx.trace_id, "032x") if ctx.is_valid else ""
        record.spanId = format(ctx.span_id, "016x") if ctx.is_valid else ""
        return True
