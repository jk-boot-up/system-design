-- Payment events from Kafka into ClickHouse, the same way as order events (001): a Kafka engine table that stores
-- nothing, facts deduplicated by event id, and unreadable messages kept with a reason.
CREATE TABLE IF NOT EXISTS analytics.payment_events_queue (raw String)
ENGINE = Kafka(payment_events);

-- One row per payment event. order_id links a payment to its order facts; the amount is what was captured or refunded.
CREATE TABLE IF NOT EXISTS analytics.payment_facts (
    event_id     UUID,
    event_type   LowCardinality(String),
    occurred_at  DateTime64(3, 'UTC'),
    payment_id   UUID,
    order_id     UUID,
    amount       Decimal(19, 2),
    currency     LowCardinality(String),
    status       LowCardinality(String),
    reason       LowCardinality(String),
    ingested_at  DateTime DEFAULT now()
) ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(occurred_at)
ORDER BY (event_type, occurred_at, event_id)
TTL toDateTime(occurred_at) + INTERVAL 25 MONTH;

CREATE TABLE IF NOT EXISTS analytics.payment_event_errors (
    received_at DateTime DEFAULT now(),
    error       String,
    raw         String
) ENGINE = MergeTree ORDER BY received_at
TTL received_at + INTERVAL 30 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS analytics.payment_facts_mv TO analytics.payment_facts AS
SELECT toUUIDOrZero(JSONExtractString(raw, 'eventId'))                                  AS event_id,
       JSONExtractString(raw, 'type')                                                   AS event_type,
       parseDateTime64BestEffortOrNull(JSONExtractString(raw, 'occurredAt'), 3, 'UTC')  AS occurred_at,
       toUUIDOrZero(JSONExtractString(raw, 'payload', 'paymentId'))                     AS payment_id,
       toUUIDOrZero(JSONExtractString(raw, 'payload', 'orderId'))                       AS order_id,
       toDecimal64OrZero(JSONExtractString(raw, 'payload', 'amount', 'amount'), 2)      AS amount,
       JSONExtractString(raw, 'payload', 'amount', 'currency')                          AS currency,
       JSONExtractString(raw, 'payload', 'status')                                      AS status,
       JSONExtractString(raw, 'payload', 'reason')                                      AS reason
FROM analytics.payment_events_queue
WHERE _error = '' AND isValidJSON(raw)
  AND toUUIDOrZero(JSONExtractString(raw, 'eventId')) != toUUID('00000000-0000-0000-0000-000000000000')
  AND JSONExtractString(raw, 'type') LIKE 'payment.%'
  AND parseDateTime64BestEffortOrNull(JSONExtractString(raw, 'occurredAt'), 3, 'UTC') IS NOT NULL;

CREATE MATERIALIZED VIEW IF NOT EXISTS analytics.payment_event_errors_mv TO analytics.payment_event_errors AS
SELECT if(_error != '', _error, 'missing or invalid eventId, type or occurredAt') AS error,
       if(_error != '', _raw_message, raw)                                         AS raw
FROM analytics.payment_events_queue
WHERE _error != '' OR NOT isValidJSON(raw)
   OR toUUIDOrZero(JSONExtractString(raw, 'eventId')) = toUUID('00000000-0000-0000-0000-000000000000')
   OR JSONExtractString(raw, 'type') NOT LIKE 'payment.%'
   OR parseDateTime64BestEffortOrNull(JSONExtractString(raw, 'occurredAt'), 3, 'UTC') IS NULL;

-- Daily payment figures, refreshed every 5 minutes over FINAL so a re-delivered event never counts twice:
-- captured and refunded money, failures, and the decline rate the payment provider is giving us.
CREATE TABLE IF NOT EXISTS analytics.payments_per_day (
    day           Date,
    currency      LowCardinality(String),
    captured      UInt64,
    failed        UInt64,
    refunded      UInt64,
    captured_sum  Decimal(38, 2),
    refunded_sum  Decimal(38, 2)
) ENGINE = MergeTree ORDER BY (day, currency);

CREATE MATERIALIZED VIEW IF NOT EXISTS analytics.payments_per_day_mv
REFRESH EVERY 5 MINUTE TO analytics.payments_per_day AS
SELECT toDate(occurred_at) AS day, currency,
       countIf(event_type = 'payment.PaymentCaptured')              AS captured,
       countIf(event_type = 'payment.PaymentFailed')                AS failed,
       countIf(event_type = 'payment.PaymentRefunded')              AS refunded,
       sumIf(amount, event_type = 'payment.PaymentCaptured')        AS captured_sum,
       sumIf(amount, event_type = 'payment.PaymentRefunded')        AS refunded_sum
FROM analytics.payment_facts FINAL
GROUP BY day, currency;
