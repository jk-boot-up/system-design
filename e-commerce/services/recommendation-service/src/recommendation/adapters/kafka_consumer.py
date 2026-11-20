"""Reads order.events; commits an offset only after the event is counted (or set aside)."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Callable

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer, TopicPartition

from ..app import CoPurchaseStore, Learned, learn

TOPIC = "order.events"
GROUP = "recommendation-service"
log = logging.getLogger(__name__)


class KafkaDeadEvents:
    """Parks one record on order.events.dlq with its key and headers (so a replay lands on the same partition and keeps
    its traceparent), plus the reason and where it came from."""

    def __init__(self, producer: AIOKafkaProducer, source=None):
        self._producer = producer
        self._source = source

    async def dead(self, value: bytes, reason: str) -> None:
        src = self._source
        headers = list(src.headers) if src is not None else []
        if src is not None:
            headers += [("x-original-topic", src.topic.encode()), ("x-original-partition", str(src.partition).encode()),
                        ("x-original-offset", str(src.offset).encode())]
        headers += [("x-original-group", GROUP.encode()), ("x-reason", reason.encode())]
        await self._producer.send_and_wait(TOPIC + ".dlq", value, key=src.key if src is not None else None,
                                           headers=headers)


async def consume(bootstrap: str, store: CoPurchaseStore, observe: Callable[[Learned], None], stop: asyncio.Event) -> None:
    consumer = AIOKafkaConsumer(TOPIC, bootstrap_servers=bootstrap, group_id=GROUP, enable_auto_commit=False,
                                auto_offset_reset="earliest", client_id="recommendation-service")
    producer = AIOKafkaProducer(bootstrap_servers=bootstrap, acks="all", client_id="recommendation-service")
    await consumer.start()
    await producer.start()
    try:
        while not stop.is_set():
            batches = await consumer.getmany(timeout_ms=500, max_records=200)
            for tp, records in batches.items():
                for record in records:
                    await _learn_until_done(record.value, store, KafkaDeadEvents(producer, record), observe, stop)
                    if stop.is_set():
                        return
                    await consumer.commit({TopicPartition(tp.topic, tp.partition): record.offset + 1})
    finally:
        await consumer.stop()
        await producer.stop()


async def _learn_until_done(value, store, dead, observe, stop: asyncio.Event) -> None:
    """Redis down holds the partition: the event is retried with back-off, never skipped."""
    delay = 0.2
    while not stop.is_set():
        try:
            observe(await learn(value, store, dead))
            return
        except Exception as e:  # noqa: BLE001 - any store or producer failure is retried the same way
            log.warning("event not counted, retrying in %.1fs: %s", delay, e)
            try:
                await asyncio.wait_for(stop.wait(), timeout=delay)
            except TimeoutError:
                pass
            delay = min(delay * 2, 30)
