"""The replay tool against a real Kafka: what it copies, with which key and headers, and what it deletes."""

import io
import json
import uuid

import pytest
from aiokafka import AIOKafkaConsumer, AIOKafkaProducer, TopicPartition
from aiokafka.admin import AIOKafkaAdminClient, NewTopic
from testcontainers.community.kafka import KafkaContainer

import dlq_replay
from dlq_replay import Parked, delete_up_to


@pytest.fixture(scope="module")
def bootstrap():
    with KafkaContainer("confluentinc/cp-kafka:8.1.0").with_kraft() as kafka:
        yield kafka.get_bootstrap_server()


async def topics(bootstrap, *names, partitions=2):
    admin = AIOKafkaAdminClient(bootstrap_servers=bootstrap)
    await admin.start()
    await admin.create_topics([NewTopic(n, partitions, 1) for n in names])
    await admin.close()


async def produce(bootstrap, topic, records):
    p = AIOKafkaProducer(bootstrap_servers=bootstrap)
    await p.start()
    for partition, key, value, headers in records:
        await p.send_and_wait(topic, value, key=key, partition=partition, headers=headers)
    await p.stop()


async def read_all(bootstrap, topic):
    c = AIOKafkaConsumer(bootstrap_servers=bootstrap, enable_auto_commit=False)
    await c.start()
    tps = [TopicPartition(topic, p) for p in c.partitions_for_topic(topic) or await dlq_replay._partitions(c, topic)]
    c.assign(tps)
    await c.seek_to_beginning(*tps)
    end = await c.end_offsets(tps)
    out = []
    while [tp for tp in tps if await c.position(tp) < end[tp]]:
        for records in (await c.getmany(timeout_ms=1000)).values():
            out.extend(records)
    await c.stop()
    return out


def spring(group, reason, trace="00-aaaa-bbbb-01"):
    return [("traceparent", trace.encode()), ("kafka_dlt-original-consumer-group", group.encode()),
            ("kafka_dlt-exception-message", reason.encode()), ("kafka_dlt-original-offset", b"\x00\x00\x00\x07")]


def go(group, reason):
    return [("x-reason", reason.encode()), ("x-original-group", group.encode()), ("x-original-topic", b"t")]


async def test_replays_matching_events_with_key_and_trace_then_deletes_only_those(bootstrap):
    main, dlq = f"t{uuid.uuid4().hex[:6]}.events", None
    dlq = main + ".dlq"
    await topics(bootstrap, main, dlq)
    await produce(bootstrap, dlq, [
        (0, b"order-1", b'{"n":1}', spring("search-service", "IndexNotFound")),
        (0, b"order-2", b'{"n":2}', go("notification-service", "unknown event version")),
        (0, b"order-3", b'{"n":3}', spring("search-service", "IndexNotFound")),
        (1, b"order-4", b'{"n":4}', spring("search-service", "IndexNotFound")),
    ])

    out = io.StringIO()
    summary = await dlq_replay.replay(bootstrap, dlq, main, "search-service", None, None, False, False, out=out)

    assert summary["parked"] == 4 and summary["replayed"] == 3
    replayed = await read_all(bootstrap, main)
    assert sorted(r.key for r in replayed) == [b"order-1", b"order-3", b"order-4"]
    first = next(r for r in replayed if r.key == b"order-1")
    headers = dict(first.headers)
    assert headers["traceparent"] == b"00-aaaa-bbbb-01"                    # still leads to the original trace
    assert headers["x-replayed-from"] == f"{dlq}/0/0".encode()
    assert not [k for k in headers if k.startswith("kafka_dlt-")]           # diagnostics dropped
    assert first.value == b'{"n":1}'
    # Same key, so the same partitioner puts the event on the partition its order's other events are on.
    assert {r.key for r in replayed if r.partition == first.partition} >= {b"order-1"}

    # Partition 0 is deleted only up to the notification event, which was not replayed; partition 1 is emptied.
    assert summary["deleted"] == {"0": 1, "1": 1}
    left = await dlq_replay.read_parked(bootstrap, dlq)
    assert [(p.partition, p.offset, p.key) for p in left] == [(0, 1, b"order-2"), (0, 2, b"order-3")]
    assert len(out.getvalue().splitlines()) == 3


async def test_dry_run_and_keep_change_nothing_on_the_dlq(bootstrap):
    main = f"t{uuid.uuid4().hex[:6]}.events"
    dlq = main + ".dlq"
    await topics(bootstrap, main, dlq, partitions=1)
    await produce(bootstrap, dlq, [(0, b"k", b"v", go("recommendation-service", "not JSON"))])

    dry = await dlq_replay.replay(bootstrap, dlq, main, None, "json", None, True, False, out=io.StringIO())
    assert dry["matching"] == 1 and dry["replayed"] == 0
    assert await read_all(bootstrap, main) == []

    kept = await dlq_replay.replay(bootstrap, dlq, main, None, None, None, False, True, out=io.StringIO())
    assert kept["replayed"] == 1 and kept["deleted"] == {}
    assert len(await dlq_replay.read_parked(bootstrap, dlq)) == 1
    assert dict((await read_all(bootstrap, main))[0].headers) == {"x-replayed-from": f"{dlq}/0/0".encode()}


async def test_list_prints_group_reason_and_value(bootstrap, capsys):
    main = f"t{uuid.uuid4().hex[:6]}.events"
    dlq = main + ".dlq"
    await topics(bootstrap, dlq, partitions=1)
    await produce(bootstrap, dlq, [(0, b"k", b'{"x":1}', spring("review-service", "Cassandra schema"))])
    out = io.StringIO()
    assert await dlq_replay.list_parked(bootstrap, dlq, None, None, True, out=out) == 1
    row = json.loads(out.getvalue())
    assert (row["group"], row["reason"], row["key"], row["value"]) == ("review-service", "Cassandra schema", "k", '{"x":1}')


def test_deletion_stops_at_the_first_event_left_parked():
    parked = [Parked(0, o, None, None) for o in (5, 6, 7)] + [Parked(1, 0, None, None)]
    assert delete_up_to(parked, {(0, 5), (0, 7), (1, 0)}) == {0: 6, 1: 1}
    assert delete_up_to(parked, {(0, 6)}) == {}
