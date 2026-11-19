# Observability

Traces, metrics and logs for every request, SLOs with burn-rate alerts, and alerts on the event pipeline (SPEC §2.2 N9,
§8.2; decision: [ADR 0013](../../design/adr/0013-observability-with-opentelemetry-alloy-and-slos-at-the-gateway.md)).
One set of rules and dashboards serves the local stack and Kubernetes.

```
 services ──OTLP──► Alloy ──► Tempo ─────────┐
 (Java: Spring Boot OTel starter,            │
  storefront: OTel Node SDK)                 ├──► Grafana  (trace ⇄ logs ⇄ metrics)
 container / pod stdout ──► Alloy ──► Loki ──┤
 /actuator/prometheus, /metrics,             │
 kafka-exporter ──scrape──► Prometheus ──────┘──► Alertmanager (Kubernetes)
                            rules: SLOs, platform alerts
```

## What is where

| Path | What |
|---|---|
| [`generate.py`](generate.py) | **The SLO table.** Generates the SLO rules, the Kubernetes PrometheusRules and the dashboards; `--check` fails when they are stale |
| [`prometheus/rules/platform.rules.yml`](prometheus/rules/platform.rules.yml) | Hand-written alerts: outbox backlog, dead-letter queues, consumer lag, a topic growing faster than its events, services down, payments, the rate limiter |
| `prometheus/rules/slo.rules.yml` | Generated: error ratios over 5 min … 3 days per SLO, and the burn-rate alerts |
| [`prometheus/tests/alerts.test.yml`](prometheus/tests/alerts.test.yml) | Unit tests of the alerts (promtool) |
| `grafana/dashboards/*.json` | Generated: *Services: rate, errors, duration*; *SLOs and error budgets*; *Checkout, events and Kafka* |
| [`alloy/compose.alloy`](alloy/compose.alloy), [`tempo/`](tempo/compose.yaml), [`loki/`](loki/compose.yaml) | The local stack's collector and back ends |
| [`kustomization.yaml`](kustomization.yaml), [`k8s/`](k8s/) | PodMonitors, PrometheusRules, dashboards ConfigMap and the network policy, included by `deploy/k8s/base` |
| [`k8s/values/`](k8s/values/) | Helm values for the stack itself (installed once per cluster) |
| [`RUNBOOK.md`](RUNBOOK.md) | What to do when each alert fires |

## SLOs

Measured at the API gateway per route (`spring_cloud_gateway_requests_seconds`), so failures between the gateway and a
service count too. Objectives are over 30 days.

| SLO | Routes | Objective | Bad request |
|---|---|---|---|
| browse-availability | catalog, search, reviews, recommendations, orders (reads) | 99.95 % | 5xx |
| cart-availability | cart, cart-items | 99.95 % | 5xx |
| checkout-availability | checkout (POST /orders) | 99.9 % | 5xx |
| product-latency | catalog, GET | 99 % under 150 ms | slower |
| search-latency | search | 99 % under 300 ms | slower |
| add-to-cart-latency | cart-items, PUT/POST | 99 % under 100 ms | slower |
| checkout-latency | checkout | 99 % under 1.5 s | slower |

The gateway exports histogram buckets at exactly these thresholds. Alerts follow the SRE workbook: **page** when the 1-hour
and 5-minute burn rates both exceed 14.4 (the budget gone in about 2 days) or the 6-hour and 30-minute rates exceed 6;
**ticket** at 3 (1 day and 2 hours) or 1 (3 days and 6 hours).

## Traces across the outbox

A request's trace does not end when its transaction commits. The outbox writer stores the request's W3C `traceparent` in
the outbox row; the relay sends the event inside that context; Kafka carries it in the record headers; consumers continue
it. One trace shows the click, the gateway, the service, the event and every consumer:

```
api-gateway   http post /api/v1/orders                       request.id = 5103febc-…
order-service   http post /api/v1/orders
order-service     order.events send   (×2, by the outbox relay, later)
cart-service        order.events process  → del (cart cleared)
inventory-service   order.events process
```

Problem responses return the request id as `traceId`; in Grafana's Explore → Tempo, `{ span.request.id = "<traceId>" }`
finds the trace.

## Run locally

```bash
cd deploy/compose
docker compose up -d                                   # core; Alloy is always on
docker compose --profile observability up -d           # + Tempo, Loki, Prometheus, Grafana, kafka-exporter
open http://localhost:3001                             # Grafana: E-commerce folder; Explore for traces and logs
```

The core stack plus this profile needs about 5 GB of Docker memory under load. In a 4 GB VM it starts, but Keycloak gets
OOM-killed and exports time out once traffic flows; there, stop the storefront and edge, and run Tempo or Loki with the core
rather than both (`docker compose --profile observability up -d tempo` …). Targets of profiles that are not running show as
down in Prometheus, and `ServiceDown` fires for them; that is expected locally.

## Check

```bash
./check.sh       # generated files up to date, promtool check and unit tests, alloy fmt, kustomize build
```

CI runs the same in `.github/workflows/e-commerce-observability.yml`.

## Kubernetes

Once per cluster, in namespace `monitoring` (versions are pinned in each values file):

```bash
helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack --version 92.18.0 -n monitoring --create-namespace -f k8s/values/kube-prometheus-stack.yaml
helm upgrade --install tempo grafana/tempo -n monitoring -f k8s/values/tempo.yaml
helm upgrade --install loki grafana/loki -n monitoring -f k8s/values/loki.yaml
helm upgrade --install alloy-sampler grafana/alloy --version 1.15.0 -n monitoring -f k8s/values/alloy-sampler.yaml
helm upgrade --install alloy grafana/alloy --version 1.15.0 -n monitoring -f k8s/values/alloy.yaml
```

The overlays then bring the PodMonitors, the rules, the dashboards and a network policy that lets Prometheus scrape the
shop and the shop send traces to Alloy. Strimzi runs the Kafka exporter (`spec.kafkaExporter`).

## Not yet covered

* notification-service (Go) and recommendation-service (Python) export metrics but no traces, and their Kafka consumers
  ignore the incoming `traceparent`.
* The edge (NGINX) logs the request id but starts no trace; the gateway starts it.
* Exemplars are stored by Prometheus but the services do not attach them to histograms yet.
