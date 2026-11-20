"""Traces continue the caller's: the gateway's traceparent on HTTP requests, the checkout's on Kafka records."""

import logging

from fastapi.testclient import TestClient
from opentelemetry import trace

from recommendation.adapters.http import Metrics, create_app
from recommendation.telemetry import TraceIds, context_of, tracer
from tests.fakes import FakeStore

TRACE = "4bf92f3577b34da6a3ce929d0e0e4736"
PARENT = f"00-{TRACE}-00f067aa0ba902b7-01"


def test_a_kafka_records_traceparent_is_read_from_its_headers():
    ctx = context_of([("x-other", b"1"), ("traceparent", PARENT.encode())])
    with tracer.start_as_current_span("order.events process", context=ctx) as span:
        assert format(span.get_span_context().trace_id, "032x") == TRACE
    with tracer.start_as_current_span("x", context=context_of(None)) as span:  # no headers: a new trace, no error
        assert format(span.get_span_context().trace_id, "032x") != TRACE


def test_requests_continue_the_gateways_trace_and_probes_are_not_traced(spans):
    client = TestClient(create_app(FakeStore(), Metrics()))
    assert client.get("/api/v1/products/TENT/recommendations", headers={"traceparent": PARENT}).status_code == 200
    client.get("/healthz")
    client.get("/metrics")
    server = [s for s in spans.get_finished_spans() if s.kind == trace.SpanKind.SERVER]
    assert len(server) == 1
    assert format(server[0].context.trace_id, "032x") == TRACE
    assert "recommendations" in server[0].name


def test_log_lines_carry_the_current_trace(caplog):
    caplog.handler.addFilter(TraceIds())
    log = logging.getLogger("recommendation.test")
    with caplog.at_level(logging.INFO):
        with tracer.start_as_current_span("x", context=context_of([("traceparent", PARENT.encode())])):
            log.info("inside")
        log.info("outside")
    inside, outside = caplog.records[-2:]
    assert inside.traceId == TRACE and len(inside.spanId) == 16
    assert outside.traceId == "" and outside.spanId == ""
