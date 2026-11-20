# dlq-replay

Lists and replays the events parked on a dead-letter topic (`<topic>.dlq`), the last step of
[runbook 15.2](../../../design/15-runbooks.md#152-dead-letter-queue-replay).

| | |
|---|---|
| Language | Python 3.14, aiokafka 0.15 |
| Reads | any `*.dlq` topic, from its log start to the end offsets when it starts |
| Writes | the main topic (`order.events.dlq` → `order.events`), with `acks=all` and idempotence |
| Deletes | replayed records from the DLQ (Kafka DeleteRecords), unless `--keep` |
| Tests | [tests/test_dlq_replay.py](tests/test_dlq_replay.py) against Kafka in a container |

## What a replay does

1. Reads every parked record and keeps those that match `--group` (the consumer group that parked it) and `--reason`
   (text in the failure reason), up to `--limit`.
2. Sends each to the main topic with **the same key** (so it lands on the partition its order's other events are on) and
   **the original headers** (`traceparent` still leads to the failed attempt's trace). Dead-letter diagnostics
   (`kafka_dlt-*`, `x-reason`, `x-original-*`) are dropped and `x-replayed-from: <dlq>/<partition>/<offset>` is added.
3. Waits for Kafka to acknowledge every record, then deletes them from the DLQ. In each partition it deletes only up to the
   first record it did not replay, so events filtered out stay parked. The DLQ's depth, and with it the
   `DeadLetterQueueNotEmpty` alert, goes back to zero once everything is replayed.

Every consumer of the main topic sees a replayed event again, not only the one that failed. That is safe because every
consumer is idempotent (processed-event ledgers, version guards, the notification sent log). If the tool stops between
sending and deleting, running it again sends the same events again, which is harmless for the same reason.

Both header styles are understood: Spring's `DeadLetterPublishingRecoverer` (search, review) writes
`kafka_dlt-original-consumer-group` and `kafka_dlt-exception-message`; notification and recommendation write
`x-original-group` and `x-reason`.

## Use

```bash
python3.14 -m venv .venv && .venv/bin/pip install -r requirements.txt
export KAFKA_BOOTSTRAP=localhost:9092

.venv/bin/python dlq_replay.py list   --dlq order.events.dlq                      # what is parked, and why
.venv/bin/python dlq_replay.py replay --dlq order.events.dlq --dry-run            # what would be replayed
.venv/bin/python dlq_replay.py replay --dlq order.events.dlq --group search-service --reason IndexNotFound
.venv/bin/python dlq_replay.py replay --dlq catalog.events.dlq --keep             # replay, leave the DLQ as it is
```

Each replayed record is printed as one JSON line on stdout; a summary (`parked`, `matching`, `replayed`, `deleted` up to
which offset per partition) goes to stderr.

In Kubernetes, build and push the image, then run it as a Job in the shop namespace (its network policy allows only Kafka):

```bash
docker build -t registry.shop.example/e-commerce/dlq-replay:latest . && docker push registry.shop.example/e-commerce/dlq-replay:latest
kubectl -n e-commerce-shop apply -f networkpolicy.yaml
kubectl -n e-commerce-shop create -f job.yaml          # edit args first: it starts as a dry run
kubectl -n e-commerce-shop logs -f job/dlq-replay
```

## Test

```bash
python3.14 -m venv .venv && .venv/bin/pip install -r tests/requirements.txt && .venv/bin/pytest
```

The tests prove: only matching events are replayed, with their key, value and `traceparent`; diagnostics are dropped and
the origin is recorded; deletion stops at the first event left parked; `--dry-run` and `--keep` change nothing on the DLQ;
`list` shows group, reason and value.
