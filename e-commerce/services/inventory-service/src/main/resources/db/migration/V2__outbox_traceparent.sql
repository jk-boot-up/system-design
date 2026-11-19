-- The W3C traceparent of the request that wrote the event, so the relay can continue its trace through Kafka.
alter table outbox add column traceparent text;
