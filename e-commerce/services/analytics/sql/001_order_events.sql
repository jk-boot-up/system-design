-- Order events from Kafka into ClickHouse. Applied in file order on first start (docker-entrypoint-initdb.d).
CREATE DATABASE IF NOT EXISTS analytics;

-- 1. The Kafka engine table reads order.events (consumer group analytics-orders). It stores nothing: materialised views
--    attached to it receive each block of messages.
CREATE TABLE IF NOT EXISTS analytics.order_events_queue (raw String)
ENGINE = Kafka(order_events);

-- 2. One row per order event. Duplicate deliveries share an event_id and the same sorting key, so ReplacingMergeTree
--    collapses them when parts merge; queries that must be exact read with FINAL.
CREATE TABLE IF NOT EXISTS analytics.order_facts (
    event_id     UUID,
    event_type   LowCardinality(String),
    occurred_at  DateTime64(3, 'UTC'),
    order_id     UUID,
    customer_key UInt64,                  -- cityHash64(customer id): no personal identifier is stored
    total        Decimal(19, 2),
    currency     LowCardinality(String),
    reason       LowCardinality(String),
    line_count   UInt16,
    ingested_at  DateTime DEFAULT now()
) ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(occurred_at)
ORDER BY (event_type, occurred_at, event_id)
TTL toDateTime(occurred_at) + INTERVAL 25 MONTH;

-- 3. Messages that are not JSON, or lack an event id, type or time, are kept here instead of being lost or blocking.
CREATE TABLE IF NOT EXISTS analytics.order_event_errors (
    received_at DateTime DEFAULT now(),
    error       String,
    raw         String
) ENGINE = MergeTree ORDER BY received_at
TTL received_at + INTERVAL 30 DAY;

-- 4. Parse each message into a fact. The *OrZero / *OrNull functions never throw, so one bad message cannot fail the
--    whole block; rows that do not parse are routed by the error view below instead.
CREATE MATERIALIZED VIEW IF NOT EXISTS analytics.order_facts_mv TO analytics.order_facts AS
SELECT toUUIDOrZero(JSONExtractString(raw, 'eventId'))                                  AS event_id,
       JSONExtractString(raw, 'type')                                                   AS event_type,
       parseDateTime64BestEffortOrNull(JSONExtractString(raw, 'occurredAt'), 3, 'UTC')  AS occurred_at,
       toUUIDOrZero(JSONExtractString(raw, 'payload', 'orderId'))                       AS order_id,
       cityHash64(JSONExtractString(raw, 'payload', 'customerId'))                      AS customer_key,
       toDecimal64OrZero(JSONExtractString(raw, 'payload', 'total', 'amount'), 2)       AS total,
       JSONExtractString(raw, 'payload', 'total', 'currency')                           AS currency,
       JSONExtractString(raw, 'payload', 'reason')                                      AS reason,
       toUInt16(JSONLength(raw, 'payload', 'lines'))                                    AS line_count
FROM analytics.order_events_queue
WHERE _error = '' AND isValidJSON(raw)
  AND toUUIDOrZero(JSONExtractString(raw, 'eventId')) != toUUID('00000000-0000-0000-0000-000000000000')
  AND JSONExtractString(raw, 'type') LIKE 'order.%'
  AND parseDateTime64BestEffortOrNull(JSONExtractString(raw, 'occurredAt'), 3, 'UTC') IS NOT NULL;

CREATE MATERIALIZED VIEW IF NOT EXISTS analytics.order_event_errors_mv TO analytics.order_event_errors AS
SELECT if(_error != '', _error, 'missing or invalid eventId, type or occurredAt') AS error,
       if(_error != '', _raw_message, raw)                                         AS raw
FROM analytics.order_events_queue
WHERE _error != '' OR NOT isValidJSON(raw)
   OR toUUIDOrZero(JSONExtractString(raw, 'eventId')) = toUUID('00000000-0000-0000-0000-000000000000')
   OR JSONExtractString(raw, 'type') NOT LIKE 'order.%'
   OR parseDateTime64BestEffortOrNull(JSONExtractString(raw, 'occurredAt'), 3, 'UTC') IS NULL;
