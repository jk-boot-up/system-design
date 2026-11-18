-- Rollups for dashboards. They are refreshable materialised views over order_facts FINAL, not insert-triggered views:
-- an insert-triggered SummingMergeTree would add a duplicate delivery's revenue twice, because ReplacingMergeTree only
-- removes the duplicate fact later, at merge time. Each refresh recomputes its window exactly and swaps the result in.

-- Revenue per minute for the last two days, at most 30 seconds old: "revenue within a minute of an order".
CREATE TABLE IF NOT EXISTS analytics.revenue_per_minute (
    minute   DateTime('UTC'),
    currency LowCardinality(String),
    orders   UInt64,
    revenue  Decimal(38, 2)
) ENGINE = MergeTree ORDER BY (minute, currency);

CREATE MATERIALIZED VIEW IF NOT EXISTS analytics.revenue_per_minute_mv
REFRESH EVERY 30 SECOND TO analytics.revenue_per_minute AS
SELECT toStartOfMinute(occurred_at) AS minute, currency, count() AS orders, sum(total) AS revenue
FROM analytics.order_facts FINAL
WHERE event_type = 'order.OrderConfirmed' AND occurred_at >= now() - INTERVAL 2 DAY
GROUP BY minute, currency;

-- Daily figures for the whole history, refreshed hourly: revenue, orders, cancellations and the cancellation rate.
CREATE TABLE IF NOT EXISTS analytics.orders_per_day (
    day           Date,
    currency      LowCardinality(String),
    placed        UInt64,
    confirmed     UInt64,
    cancelled     UInt64,
    revenue       Decimal(38, 2),
    avg_order     Decimal(38, 2),
    customers     UInt64
) ENGINE = MergeTree ORDER BY (day, currency);

CREATE MATERIALIZED VIEW IF NOT EXISTS analytics.orders_per_day_mv
REFRESH EVERY 1 HOUR TO analytics.orders_per_day AS
SELECT toDate(occurred_at) AS day, currency,
       countIf(event_type = 'order.OrderPlaced')                     AS placed,
       countIf(event_type = 'order.OrderConfirmed')                  AS confirmed,
       countIf(event_type = 'order.OrderCancelled')                  AS cancelled,
       sumIf(total, event_type = 'order.OrderConfirmed')             AS revenue,
       if(confirmed = 0, 0, revenue / confirmed)                     AS avg_order,
       uniqExactIf(customer_key, event_type = 'order.OrderConfirmed') AS customers
FROM analytics.order_facts FINAL
GROUP BY day, currency;
