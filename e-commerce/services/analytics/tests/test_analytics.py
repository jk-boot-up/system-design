"""The analytics image (ClickHouse + schema) reading a real Kafka over a shared Docker network."""

import json
import time
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

import pytest
from aiokafka import AIOKafkaProducer
from aiokafka.admin import AIOKafkaAdminClient, NewTopic
from testcontainers.community.kafka import KafkaContainer
from testcontainers.core.container import DockerContainer
from testcontainers.core.image import DockerImage
from testcontainers.core.network import Network

ROOT = Path(__file__).parent.parent
TOPIC = "order.events"


class ClickHouse:
    def __init__(self, url: str):
        self.url = url

    def query(self, sql: str) -> list[dict]:
        req = urllib.request.Request(self.url + "/?" + urllib.parse.urlencode({"default_format": "JSONEachRow", "output_format_json_quote_64bit_integers": 0}),
                                     data=sql.encode())
        with urllib.request.urlopen(req, timeout=30) as r:
            return [json.loads(line) for line in r.read().decode().splitlines() if line]

    def scalar(self, sql: str):
        rows = self.query(sql)
        return next(iter(rows[0].values())) if rows else None


@pytest.fixture(scope="module")
def stack():
    with Network() as network, DockerImage(path=str(ROOT), tag="e-commerce/analytics:test") as image:
        kafka = KafkaContainer("confluentinc/cp-kafka:8.1.0").with_kraft().with_network(network) \
            .with_network_aliases("kafka")
        with kafka:
            ch = DockerContainer(str(image)).with_network(network).with_exposed_ports(8123) \
                .with_env("KAFKA_BROKERS", "kafka:9092")
            with ch:
                url = f"http://{ch.get_container_host_ip()}:{ch.get_exposed_port(8123)}"
                db = ClickHouse(url)
                for _ in range(180):  # ready once the init scripts have created the last view
                    try:
                        if db.scalar("SELECT count() FROM system.tables WHERE database = 'analytics' "
                                     "AND name = 'orders_per_day_mv'") == 1:
                            break
                    except OSError:
                        pass
                    time.sleep(1)
                else:
                    raise AssertionError("schema not created")
                yield db, kafka.get_bootstrap_server()


def event(kind: str, amount: str, *, event_id=None, reason="", lines=1, minutes_ago=0) -> bytes:
    occurred = time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime(time.time() - minutes_ago * 60))
    payload = {"orderId": str(uuid.uuid4()), "customerId": "customer-" + amount, "customerEmail": "ada@example.com",
               "total": {"amount": amount, "currency": "EUR"},
               "lines": [{"sku": f"SKU-{i}", "quantity": 1} for i in range(lines)]}
    if reason:
        payload["reason"] = reason
    return json.dumps({"eventId": event_id or str(uuid.uuid4()), "type": f"order.{kind}", "version": 1,
                       "occurredAt": occurred, "aggregateId": payload["orderId"], "payload": payload}).encode()


def wait_until(check, seconds=60, explain=lambda: ""):
    for _ in range(seconds * 2):
        if check():
            return
        time.sleep(0.5)
    raise AssertionError("timed out: " + explain())


async def test_events_become_exact_facts_rollups_and_dashboards(stack):
    db, bootstrap = stack
    admin = AIOKafkaAdminClient(bootstrap_servers=bootstrap)
    await admin.start()
    await admin.create_topics([NewTopic(TOPIC, 3, 1)])
    await admin.close()

    duplicate_id = str(uuid.uuid4())
    producer = AIOKafkaProducer(bootstrap_servers=bootstrap)
    await producer.start()
    messages = [
        event("OrderPlaced", "100.00", lines=2),
        event("OrderConfirmed", "100.00", event_id=duplicate_id, lines=2),
        event("OrderConfirmed", "100.00", event_id=duplicate_id, lines=2),  # the relay delivered it twice
        event("OrderConfirmed", "79.90", minutes_ago=3),
        event("OrderCancelled", "25.00", reason="PAYMENT_DECLINED", lines=0),
        b"this is not json",
        json.dumps({"type": "order.OrderConfirmed", "payload": {}}).encode(),  # no event id
    ]
    for i, m in enumerate(messages):
        await producer.send_and_wait(TOPIC, m, key=str(i).encode())
    await producer.stop()

    # Raw row count depends on when background merges collapse the duplicate; FINAL is exact either way.
    wait_until(lambda: db.scalar("SELECT count() FROM analytics.order_facts FINAL") == 4
               and db.scalar("SELECT count() FROM analytics.order_event_errors") == 2,
               explain=lambda: json.dumps(db.query(
                   "SELECT (SELECT count() FROM analytics.order_facts) AS facts, "
                   "(SELECT count() FROM analytics.order_event_errors) AS errors, "
                   "(SELECT groupArray(exceptions.text) FROM system.kafka_consumers) AS kafka")))

    time.sleep(3)  # nothing else arrives
    assert db.scalar("SELECT count() FROM analytics.order_facts FINAL") == 4
    assert db.scalar("SELECT toString(sum(total)) FROM analytics.order_facts FINAL "
                     "WHERE event_type = 'order.OrderConfirmed'") == "179.9"
    assert db.scalar("SELECT reason FROM analytics.order_facts FINAL WHERE event_type = 'order.OrderCancelled'") \
        == "PAYMENT_DECLINED"
    # No personal identifier is stored.
    columns = {r["name"] for r in db.query("DESCRIBE analytics.order_facts")}
    assert not {"customer_id", "customer_email"} & columns
    errors = {e["raw"]: e["error"] for e in db.query("SELECT error, raw FROM analytics.order_event_errors")}
    assert set(errors) == {"this is not json", '{"type": "order.OrderConfirmed", "payload": {}}'}
    assert errors['{"type": "order.OrderConfirmed", "payload": {}}'].startswith("missing or invalid")

    # Rollups count the duplicate once.
    db.query("SYSTEM REFRESH VIEW analytics.revenue_per_minute_mv")
    db.query("SYSTEM REFRESH VIEW analytics.orders_per_day_mv")
    db.query("SYSTEM WAIT VIEW analytics.revenue_per_minute_mv")
    db.query("SYSTEM WAIT VIEW analytics.orders_per_day_mv")
    assert db.scalar("SELECT sum(orders) FROM analytics.revenue_per_minute") == 2
    assert db.scalar("SELECT toString(sum(revenue)) FROM analytics.revenue_per_minute") == "179.9"
    day = db.query("SELECT placed, confirmed, cancelled, toString(revenue) AS revenue, customers FROM analytics.orders_per_day")
    assert day == [{"placed": 1, "confirmed": 2, "cancelled": 1, "revenue": "179.9", "customers": 2}]

    # Every dashboard query runs (Grafana's time macro replaced by a fixed window).
    dashboard = json.loads((ROOT / "grafana" / "dashboards" / "orders.json").read_text())
    for panel in dashboard["panels"]:
        sql = panel["targets"][0]["rawSql"]
        sql = sql.replace("$__timeFilter(minute)", "minute >= now() - INTERVAL 6 HOUR") \
                 .replace("$__timeFilter(day)", "day >= today() - 30")
        rows = db.query(sql)
        assert rows, panel["title"]
