#!/usr/bin/env python3
"""List or replay the events parked on a dead-letter topic (<topic>.dlq).

  dlq_replay.py list   --dlq order.events.dlq [--group G] [--reason TEXT] [--json]
  dlq_replay.py replay --dlq order.events.dlq [--to order.events] [--group G] [--reason TEXT] [--limit N]
                       [--dry-run] [--keep]

Replay copies each matching record back onto the main topic with its key and its original headers (so `traceparent`
still links to the trace of the request that produced it), drops the dead-letter diagnostics, and adds
`x-replayed-from: <dlq>/<partition>/<offset>`. Every consumer of the main topic sees the event again; they are
idempotent, so the ones that already handled it do nothing.

After every replayed record is acknowledged, the records are deleted from the DLQ (Kafka DeleteRecords), which brings its
depth and the DeadLetterQueueNotEmpty alert back to zero. Deletion stops at the first record that was not replayed in
each partition, so filtered-out events stay parked. --keep leaves the DLQ as it is.

Records written by Spring (search, review) carry `kafka_dlt-*` headers; notification and recommendation write
`x-reason` and `x-original-*`. Both are understood. Only events present when the command starts are considered.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
from dataclasses import dataclass, field

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer, TopicPartition
from aiokafka.admin import AIOKafkaAdminClient, RecordsToDelete

GROUP_HEADERS = ("kafka_dlt-original-consumer-group", "x-original-group")
REASON_HEADERS = ("kafka_dlt-exception-message", "x-reason")
TOPIC_HEADERS = ("kafka_dlt-original-topic", "x-original-topic")
# Diagnostics added when the event was parked; they mean nothing on the main topic.
DROPPED_PREFIXES = ("kafka_dlt-", "x-reason", "x-original-", "x-replayed-from")


@dataclass
class Parked:
    partition: int
    offset: int
    key: bytes | None
    value: bytes | None
    headers: list[tuple[str, bytes]] = field(default_factory=list)

    def header(self, names: tuple[str, ...]) -> str:
        for k, v in self.headers:
            if k in names and v is not None:
                return v.decode("utf-8", "replace")
        return ""

    @property
    def group(self) -> str:
        return self.header(GROUP_HEADERS)

    @property
    def reason(self) -> str:
        return self.header(REASON_HEADERS)

    def replay_headers(self, dlq: str) -> list[tuple[str, bytes]]:
        kept = [(k, v) for k, v in self.headers if not k.startswith(DROPPED_PREFIXES)]
        return kept + [("x-replayed-from", f"{dlq}/{self.partition}/{self.offset}".encode())]

    def describe(self) -> dict:
        value = (self.value or b"").decode("utf-8", "replace")
        return {"partition": self.partition, "offset": self.offset,
                "key": self.key.decode("utf-8", "replace") if self.key is not None else None,
                "group": self.group, "reason": self.reason[:300],
                "traceparent": self.header(("traceparent",)), "value": value[:500]}


def matches(p: Parked, group: str | None, reason: str | None) -> bool:
    return (group is None or p.group == group) and (reason is None or reason.lower() in p.reason.lower())


async def read_parked(bootstrap: str, dlq: str) -> list[Parked]:
    """Every record from the log start to the end offsets as they were when this started."""
    consumer = AIOKafkaConsumer(bootstrap_servers=bootstrap, enable_auto_commit=False, client_id="dlq-replay",
                                isolation_level="read_committed")
    await consumer.start()
    try:
        partitions = consumer.partitions_for_topic(dlq) or await _partitions(consumer, dlq)
        if not partitions:
            raise SystemExit(f"topic {dlq} does not exist")
        tps = [TopicPartition(dlq, p) for p in sorted(partitions)]
        consumer.assign(tps)
        start = await consumer.beginning_offsets(tps)
        end = await consumer.end_offsets(tps)
        for tp in tps:
            consumer.seek(tp, start[tp])
        pending = {tp for tp in tps if end[tp] > start[tp]}
        parked: list[Parked] = []
        while pending:
            batches = await consumer.getmany(*pending, timeout_ms=1000)
            for tp, records in batches.items():
                for r in records:
                    if r.offset < end[tp]:
                        parked.append(Parked(tp.partition, r.offset, r.key, r.value, list(r.headers)))
                if await consumer.position(tp) >= end[tp]:
                    pending.discard(tp)
        return sorted(parked, key=lambda p: (p.partition, p.offset))
    finally:
        await consumer.stop()


async def _partitions(consumer: AIOKafkaConsumer, topic: str):
    await consumer._client.force_metadata_update()  # noqa: SLF001 - aiokafka has no public refresh
    return consumer.partitions_for_topic(topic)


def delete_up_to(parked: list[Parked], replayed: set[tuple[int, int]]) -> dict[int, int]:
    """Per partition, the offset before which every record was replayed (records before it may be deleted)."""
    result: dict[int, int] = {}
    for partition in sorted({p.partition for p in parked}):
        upto = None
        for p in (x for x in parked if x.partition == partition):
            if (p.partition, p.offset) not in replayed:
                break
            upto = p.offset + 1
        if upto is not None:
            result[partition] = upto
    return result


async def replay(bootstrap: str, dlq: str, to: str, group: str | None, reason: str | None, limit: int | None,
                 dry_run: bool, keep: bool, out=sys.stdout) -> dict:
    parked = await read_parked(bootstrap, dlq)
    chosen = [p for p in parked if matches(p, group, reason)][: limit or None]
    summary = {"dlq": dlq, "to": to, "parked": len(parked), "matching": len(chosen), "replayed": 0, "deleted": {}}
    for p in chosen:
        print(json.dumps({"action": "would replay" if dry_run else "replay", **p.describe()}), file=out)
    if dry_run or not chosen:
        return summary

    producer = AIOKafkaProducer(bootstrap_servers=bootstrap, acks="all", enable_idempotence=True, client_id="dlq-replay")
    await producer.start()
    try:
        futures = [await producer.send(to, p.value, key=p.key, headers=p.replay_headers(dlq)) for p in chosen]
        await asyncio.gather(*futures)  # every record acknowledged before anything is deleted
    finally:
        await producer.stop()
    summary["replayed"] = len(chosen)

    if not keep:
        upto = delete_up_to(parked, {(p.partition, p.offset) for p in chosen})
        if upto:
            admin = AIOKafkaAdminClient(bootstrap_servers=bootstrap, client_id="dlq-replay")
            await admin.start()
            try:
                await admin.delete_records({TopicPartition(dlq, part): RecordsToDelete(before_offset=o)
                                            for part, o in upto.items()})
            finally:
                await admin.close()
        summary["deleted"] = {str(k): v for k, v in upto.items()}
    return summary


async def list_parked(bootstrap: str, dlq: str, group: str | None, reason: str | None, as_json: bool, out=sys.stdout) -> int:
    parked = [p for p in await read_parked(bootstrap, dlq) if matches(p, group, reason)]
    for p in parked:
        d = p.describe()
        if as_json:
            print(json.dumps(d), file=out)
        else:
            print(f"{dlq}/{d['partition']}/{d['offset']}  key={d['key']}  group={d['group'] or '-'}  "
                  f"reason={d['reason'] or '-'}\n    {d['value']}", file=out)
    print(f"{len(parked)} parked event(s)", file=sys.stderr)
    return len(parked)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["list", "replay"])
    ap.add_argument("--bootstrap", default=os.environ.get("KAFKA_BOOTSTRAP", "localhost:9092"))
    ap.add_argument("--dlq", required=True, help="dead-letter topic, for example order.events.dlq")
    ap.add_argument("--to", help="target topic (default: the DLQ name without .dlq)")
    ap.add_argument("--group", help="only events parked by this consumer group")
    ap.add_argument("--reason", help="only events whose reason contains this text (case-insensitive)")
    ap.add_argument("--limit", type=int, help="replay at most this many events")
    ap.add_argument("--json", action="store_true", help="list: one JSON object per event")
    ap.add_argument("--dry-run", action="store_true", help="replay: show what would be replayed, change nothing")
    ap.add_argument("--keep", action="store_true", help="replay: leave the replayed events on the DLQ")
    a = ap.parse_args(argv)
    if not a.dlq.endswith(".dlq") and not a.to:
        ap.error("--to is required when the DLQ name does not end in .dlq")
    to = a.to or a.dlq[: -len(".dlq")]
    if a.command == "list":
        asyncio.run(list_parked(a.bootstrap, a.dlq, a.group, a.reason, a.json))
        return 0
    summary = asyncio.run(replay(a.bootstrap, a.dlq, to, a.group, a.reason, a.limit, a.dry_run, a.keep))
    print(json.dumps(summary), file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
