"""Real Redis and Kafka. Run with: pytest -m containers"""

import asyncio

import pytest
from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from aiokafka.admin import AIOKafkaAdminClient, NewTopic
from fastapi.testclient import TestClient
from redis.asyncio import Redis
from testcontainers.community.kafka import KafkaContainer
from testcontainers.community.redis import RedisContainer

from recommendation.adapters import redis_store
from recommendation.adapters.http import Metrics, create_app
from recommendation.adapters.kafka_consumer import TOPIC, consume
from recommendation.adapters.redis_store import RedisCoPurchaseStore
from recommendation.domain import Purchase
from tests.events import confirmed

pytestmark = pytest.mark.containers


@pytest.fixture(scope="module")
def containers():
    with RedisContainer("redis:8-alpine") as redis, KafkaContainer("confluentinc/cp-kafka:8.1.0").with_kraft() as kafka:
        yield f"redis://{redis.get_container_host_ip()}:{redis.get_exposed_port(6379)}", kafka.get_bootstrap_server()


async def eventually(check, seconds=30):
    for _ in range(seconds * 5):
        if await check():
            return
        await asyncio.sleep(0.2)
    raise AssertionError("timed out")


async def test_kafka_to_redis_counts_each_order_once_and_dead_letters_garbage(containers, spans):
    redis_url, bootstrap = containers
    redis = Redis.from_url(redis_url)
    store = RedisCoPurchaseStore(redis)
    admin = AIOKafkaAdminClient(bootstrap_servers=bootstrap)
    await admin.start()
    await admin.create_topics([NewTopic(TOPIC, 3, 1), NewTopic(TOPIC + ".dlq", 3, 1)])
    await admin.close()

    producer = AIOKafkaProducer(bootstrap_servers=bootstrap)
    await producer.start()
    for _ in range(3):  # the same order delivered three times
        await producer.send_and_wait(TOPIC, confirmed("e1", "TENT", "STOVE", "MUG"), key=b"o-e1")
    # e2 carries the trace of the checkout that wrote it (order-service's outbox sets traceparent).
    await producer.send_and_wait(TOPIC, confirmed("e2", "TENT", "STOVE"), key=b"o-e2",
                                 headers=[("traceparent", b"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")])
    await producer.send_and_wait(TOPIC, confirmed("e3", "TENT"), key=b"o-e3")
    await producer.send_and_wait(TOPIC, b"not json", key=b"x")
    await producer.stop()

    results = []
    stop = asyncio.Event()
    task = asyncio.create_task(consume(bootstrap, store, results.append, stop))
    try:
        await eventually(lambda: _top_is(store, "TENT", [("STOVE", 2), ("MUG", 1)]))
        await eventually(lambda: _async(len(results) == 6))
        assert sorted(r.value for r in results) == ["counted", "counted", "dead_letter", "duplicate", "duplicate", "ignored"]
        assert [(r.sku, r.score) for r in await store.top("MUG", 10)] == [("TENT", 1), ("STOVE", 1)]  # ties: member descending
        consumed = [s for s in spans.get_finished_spans() if s.name == TOPIC + " process"]
        assert len(consumed) == 6
        e2 = [s for s in consumed if format(s.context.trace_id, "032x") == "4bf92f3577b34da6a3ce929d0e0e4736"]
        assert len(e2) == 1 and e2[0].attributes["recommendation.learned"] == "counted"
        assert e2[0].parent is not None and format(e2[0].parent.span_id, "016x") == "00f067aa0ba902b7"
    finally:
        stop.set()
        await task

    dlq = AIOKafkaConsumer(TOPIC + ".dlq", bootstrap_servers=bootstrap, auto_offset_reset="earliest", group_id="test")
    await dlq.start()
    try:
        batch = await dlq.getmany(timeout_ms=10_000)
        records = [r for rs in batch.values() for r in rs]
        assert [r.value for r in records] == [b"not json"]
        headers = dict(records[0].headers)
        assert headers["x-reason"].startswith(b"not JSON")
        # Kept for the replay tool: the key (same partition on replay) and where the event came from.
        assert records[0].key is not None
        assert headers["x-original-group"] == b"recommendation-service" and headers["x-original-topic"] == TOPIC.encode()
    finally:
        await dlq.stop()

    # The HTTP API reads the same sorted sets.
    client = TestClient(create_app(RedisCoPurchaseStore(Redis.from_url(redis_url)), Metrics()))
    body = client.get("/api/v1/products/TENT/recommendations?limit=1").json()
    assert body == {"sku": "TENT", "items": [{"sku": "STOVE", "score": 2}]}
    await redis.aclose()


async def test_concurrent_duplicates_count_once_and_lists_are_trimmed(containers, monkeypatch):
    redis_url, _ = containers
    redis = Redis.from_url(redis_url)
    await redis.flushall()
    store = RedisCoPurchaseStore(redis)
    purchase = Purchase("same-event", ("A1", "B1"))
    outcomes = await asyncio.gather(*(store.record(purchase) for _ in range(20)))
    assert outcomes.count(True) == 1
    assert await redis.zscore("reco:also:A1", "B1") == 1
    assert 0 < await redis.ttl("reco:seen:same-event") <= redis_store.SEEN_TTL_SECONDS

    monkeypatch.setattr(redis_store, "TOP_KEPT", 3)
    await store.record(Purchase("big", tuple(f"P{i}" for i in range(6))))
    assert await redis.zcard("reco:also:P0") == 3
    await redis.aclose()


async def _top_is(store, sku, expected):
    return [(r.sku, r.score) for r in await store.top(sku, 10)] == expected


async def _async(value):
    return value
