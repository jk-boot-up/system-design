"""Runs the HTTP API and the Kafka consumer in one process: python -m recommendation.main"""

from __future__ import annotations

import asyncio
import logging
import os
import signal

import uvicorn
from redis.asyncio import Redis

from .adapters.http import Metrics, create_app
from .adapters.kafka_consumer import consume
from .adapters.redis_store import RedisCoPurchaseStore
from .telemetry import TraceIds, setup


async def main() -> None:
    logging.basicConfig(level=logging.INFO,
                        format='{"time":"%(asctime)s","level":"%(levelname)s","logger":"%(name)s","msg":"%(message)s",'
                               '"traceId":"%(traceId)s","spanId":"%(spanId)s"}')
    for handler in logging.getLogger().handlers:
        handler.addFilter(TraceIds())
    provider = setup()
    redis = Redis.from_url(os.getenv("REDIS_URL", "redis://localhost:6379"), socket_timeout=0.2, socket_connect_timeout=0.5)
    store = RedisCoPurchaseStore(redis)
    metrics = Metrics()
    stop = asyncio.Event()
    app = create_app(store, metrics)
    server = uvicorn.Server(uvicorn.Config(app, host="0.0.0.0", port=int(os.getenv("PORT", "8089")), log_level="warning"))
    server.install_signal_handlers = lambda: None
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)

    async def serve():
        task = asyncio.create_task(server.serve())
        await stop.wait()
        server.should_exit = True
        await task

    try:
        await asyncio.gather(serve(), consume(os.getenv("KAFKA_BOOTSTRAP", "localhost:9092"), store, metrics.observe, stop))
    finally:
        await redis.aclose()
        if provider is not None:
            provider.shutdown()


if __name__ == "__main__":
    asyncio.run(main())
