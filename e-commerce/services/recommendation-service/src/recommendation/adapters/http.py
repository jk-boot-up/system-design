"""Public API: GET /api/v1/products/{sku}/recommendations, plus health and metrics."""

from __future__ import annotations

from fastapi import FastAPI, Query, Request
from fastapi.responses import JSONResponse, Response
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from prometheus_client import CONTENT_TYPE_LATEST, CollectorRegistry, Counter, generate_latest
from pydantic import BaseModel
from redis.exceptions import RedisError

from ..app import MAX_LIMIT, CoPurchaseStore, Learned, recommend
from ..domain import SKU


class Item(BaseModel):
    sku: str
    score: int


class Recommendations(BaseModel):
    sku: str
    items: list[Item]


def problem(status: int, code: str, detail: str, headers: dict[str, str] | None = None) -> JSONResponse:
    titles = {400: "Bad Request", 503: "Service Unavailable"}
    return JSONResponse({"type": "about:blank", "title": titles.get(status, "Error"), "status": status, "code": code,
                         "detail": detail}, status_code=status, media_type="application/problem+json", headers=headers)


class Metrics:
    def __init__(self) -> None:
        self.registry = CollectorRegistry()
        self.events = Counter("recommendation_events", "Kafka events by outcome", ["result"], registry=self.registry)

    def observe(self, result: Learned) -> None:
        self.events.labels(result.value).inc()


def create_app(store: CoPurchaseStore, metrics: Metrics, ready=lambda: True) -> FastAPI:
    app = FastAPI(title="recommendation-service", version="0.1.0", docs_url=None, redoc_url=None)

    @app.get("/api/v1/products/{sku}/recommendations", response_model=Recommendations)
    async def get_recommendations(sku: str, limit: int = Query(10)):
        if not SKU.match(sku):
            return problem(400, "INVALID_SKU", f"{sku!r} is not a SKU")
        if not 1 <= limit <= MAX_LIMIT:
            return problem(400, "INVALID_LIMIT", f"limit must be between 1 and {MAX_LIMIT}")
        try:
            items = await recommend(sku, limit, store)
        except RedisError:
            # Recommendations are optional on a product page: answer fast so the page can render without them.
            return problem(503, "DEPENDENCY_UNAVAILABLE", "Recommendations are temporarily unavailable", {"Retry-After": "5"})
        body = Recommendations(sku=sku, items=[Item(sku=i.sku, score=i.score) for i in items])
        return JSONResponse(body.model_dump(), headers={"Cache-Control": "public, max-age=300"})

    @app.get("/healthz", include_in_schema=False)
    async def healthz():
        return Response("ok\n", media_type="text/plain")

    @app.get("/readyz", include_in_schema=False)
    async def readyz(request: Request):
        return Response("ready\n", media_type="text/plain") if ready() else Response("starting\n", status_code=503)

    @app.get("/metrics", include_in_schema=False)
    async def metrics_endpoint():
        return Response(generate_latest(metrics.registry), media_type=CONTENT_TYPE_LATEST)

    # A server span per request, continuing the gateway's traceparent; probes and scrapes are not traced.
    FastAPIInstrumentor.instrument_app(app, excluded_urls="healthz,readyz,metrics")
    return app
