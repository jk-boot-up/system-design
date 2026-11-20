import os

import pytest
from opentelemetry import propagate, trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator

# Docker Desktop exposes the engine's socket inside its VM at /var/run/docker.sock; the clean-up container (Ryuk) mounts
# that path, not the host-side one the client connects through.
os.environ.setdefault("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")

# Spans are recorded in memory. A tracer provider can be set once per process; the service's module-level tracers are
# proxies, so they pick this one up.
_exporter = InMemorySpanExporter()
_provider = TracerProvider()
_provider.add_span_processor(SimpleSpanProcessor(_exporter))
trace.set_tracer_provider(_provider)
propagate.set_global_textmap(TraceContextTextMapPropagator())


@pytest.fixture
def spans() -> InMemorySpanExporter:
    """The spans finished during the test."""
    _exporter.clear()
    return _exporter
