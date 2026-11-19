# 0013. Observability with OpenTelemetry, Grafana Alloy and SLOs measured at the gateway

| | |
|---|---|
| Status | Accepted |
| Date | 2026-11-19 |

## Context

SPEC §2.2 N9 and §8.2 ask for traces, metrics and logs for every request, multi-window burn-rate alerts on the availability
and latency SLOs, and alerts on dead-letter queues and outbox backlog. The services are Java (Spring Boot 4), Node (the
storefront), Go and Python, and much of the work happens after a request returns: events leave through transactional
outboxes and are handled by Kafka consumers. The local stack must run in a 4 GB Docker VM.

While building the performance tests we found a failure none of this would have caught: an outbox relay re-sent one batch
4,000 times a second for two hours (30 million duplicates) because a Kafka partition was rejecting writes. Search fell hours
behind and the disk filled; every service still looked healthy.

## Decision

* **Traces:** OpenTelemetry. Java services use Spring Boot's OpenTelemetry starter (Micrometer Observation bridged to the
  OpenTelemetry SDK); the storefront uses the OpenTelemetry Node SDK (HTTP server and fetch). Context travels in W3C
  `traceparent` headers over HTTP and Kafka. **Across the outbox**, the writer stores the request's `traceparent` in the
  outbox row and the relay sends the event inside it, so one trace runs from the click to every consumer.
* **Collector:** Grafana Alloy, an OpenTelemetry Collector distribution that also reads container and pod logs. Services
  sample every request; in Kubernetes, node agents route each trace by trace id to a sampler tier that keeps errors, slow
  traces and 10 % of the rest (tail sampling).
* **Back ends:** Tempo (traces), Loki (logs, with `trace_id` as structured metadata), Prometheus (metrics, exemplars),
  Grafana (dashboards linking all three), Alertmanager.
* **SLOs** are measured at the API gateway per route, where a customer's request starts and ends, from one table in
  `deploy/observability/generate.py`, which generates the recording rules, the burn-rate alerts (Google SRE workbook:
  14.4x/1 h and 6x/6 h page, 3x/1 d and 1x/3 d open a ticket) and the dashboards. Alert rules have unit tests (promtool).
* **Outbox health is a first-class signal:** every relay reports its backlog and the age of its oldest unpublished event,
  and an alert compares each topic's growth with what its outbox published.

## Alternatives considered

| Option | Why not |
|---|---|
| OpenTelemetry Java agent (bytecode instrumentation) | SPEC §8.2 named it; Spring Boot 4 now instruments the same libraries natively, without an agent in every image or a second instrumentation model next to Micrometer's metrics |
| OpenTelemetry Collector (contrib) instead of Alloy | Same trace pipeline, but no Docker log source for the local stack; Alloy covers traces and logs in one agent, in compose and Kubernetes |
| Elastic APM, Datadog | Vendor-specific agents and storage; the open standard keeps the back end replaceable |
| SLOs per service from each service's own metrics | Misses failures between the gateway and a service (timeouts, circuit breakers, fallbacks), which the customer does see |
| Head sampling at 10 % in the services | Would drop most traces of the rare failures that are worth reading |

## Consequences

* Every outbox table has a `traceparent` column (migration V2 in catalog, inventory, order and payment).
* Problem responses keep returning the request id as `traceId` (the public contract); the gateway tags its span with
  `request.id`, so support finds the whole trace from what the customer quotes.
* Alloy is part of the core local stack (about 100 MB); Tempo, Loki, Prometheus and Grafana come with the `observability`
  profile.
* Go (notification) and Python (recommendation) services expose metrics but are not yet traced; their Kafka consumers
  ignore the `traceparent` header. Tracing them is the next step for full coverage.
