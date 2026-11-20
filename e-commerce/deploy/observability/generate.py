#!/usr/bin/env python3
"""Generates the SLO rules, the Kubernetes PrometheusRule and the Grafana dashboards from the tables below.

The SLOs (SPEC 2.2 N1 availability, N2 latency) are measured where a customer feels them: at the API gateway, per route.
For each SLO this writes recording rules of the error ratio over the burn-rate windows and the multi-window, multi-burn-rate
alerts of the Google SRE workbook (chapter 5): page when the error budget would be gone in about 2 days (14.4x over 1 h)
or 5 days (6x over 6 h); open a ticket at 3x over 1 day and 1x over 3 days. A short window must agree with each long one,
so an alert stops soon after the problem does.

  ./generate.py            write prometheus/rules/slo.rules.yml, k8s/prometheusrule.yaml, grafana/dashboards/*.json
  ./generate.py --check    fail if any of them is out of date (CI)

Standard library only: the outputs are JSON, and YAML is written as JSON-compatible flow where it is not plain text.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent

# ── SLOs ──────────────────────────────────────────────────────────────────────────────────────────────────────────────
# name, objective, gateway routes, methods, kind, latency threshold (seconds, one of the gateway's SLO buckets)
SLOS = [
    ("browse-availability", 0.9995, "catalog|search|reviews|recommendations|orders", ".*", "availability", None),
    ("cart-availability", 0.9995, "cart|cart-items", ".*", "availability", None),
    ("checkout-availability", 0.999, "checkout", ".*", "availability", None),
    ("product-latency", 0.99, "catalog", "GET", "latency", "0.15"),
    ("search-latency", 0.99, "search", "GET", "latency", "0.3"),
    ("add-to-cart-latency", 0.99, "cart-items", "PUT|POST", "latency", "0.1"),
    ("checkout-latency", 0.99, "checkout", "POST", "latency", "1.5"),
]
WINDOWS = ["5m", "30m", "1h", "2h", "6h", "1d", "3d"]
# (severity, burn rate, long window, short window)
BURNS = [("page", 14.4, "1h", "5m"), ("page", 6, "6h", "30m"), ("ticket", 3, "1d", "2h"), ("ticket", 1, "3d", "6h")]
METRIC = "spring_cloud_gateway_requests_seconds"


def selector(routes: str, methods: str, extra: str = "") -> str:
    s = f'routeId=~"{routes}"'
    if methods != ".*":
        s += f', httpMethod=~"{methods}"'
    return "{" + s + (", " + extra if extra else "") + "}"


def slo_rules() -> dict:
    record, alerts = [], []
    for name, objective, routes, methods, kind, threshold in SLOS:
        sel = selector(routes, methods)
        for w in WINDOWS:
            total = f"sum(rate({METRIC}_count{sel}[{w}]))"
            if kind == "availability":
                errors = selector(routes, methods, 'outcome="SERVER_ERROR"')
                expr = f"sum(rate({METRIC}_count{errors}[{w}]))\n/\n{total}"
            else:
                fast = selector(routes, methods, f'le="{threshold}"')
                expr = f"1 - (\n  sum(rate({METRIC}_bucket{fast}[{w}]))\n  /\n  {total}\n)"
            record.append({"record": f"slo:error_ratio:rate{w}", "expr": expr, "labels": {"slo": name}})
        budget = round(1 - objective, 6)
        what = (f"{name}: more than {budget:.4%} of requests failing" if kind == "availability"
                else f"{name}: more than {budget:.0%} of requests slower than {float(threshold) * 1000:g} ms")
        for severity in ("page", "ticket"):
            parts = [f'(slo:error_ratio:rate{long}{{slo="{name}"}} > ({rate} * {budget}) and '
                     f'slo:error_ratio:rate{short}{{slo="{name}"}} > ({rate} * {budget}))'
                     for sev, rate, long, short in BURNS if sev == severity]
            alerts.append({
                "alert": "ErrorBudgetBurn" + ("Fast" if severity == "page" else "Slow"),
                "expr": "\n or ".join(parts),
                "labels": {"severity": severity, "slo": name},
                "annotations": {
                    "summary": f"{name} is burning its error budget {'fast' if severity == 'page' else 'steadily'}",
                    "description": f"{what} (objective {objective:.2%} over 30 days). Check the SLO dashboard, then the "
                                   "service's RED dashboard and the traces of failing requests.",
                    "runbook_url": "https://github.com/jk-boot-up/system-design/blob/main/e-commerce/deploy/observability/RUNBOOK.md#error-budget-burn",
                },
            })
    return {"groups": [{"name": "slo-recording", "interval": "30s", "rules": record},
                       {"name": "slo-alerts", "rules": alerts}]}


# ── dashboards ────────────────────────────────────────────────────────────────────────────────────────────────────────

class Dashboard:
    """A Grafana dashboard built panel by panel on a 24-column grid."""

    def __init__(self, uid: str, title: str, description: str, variables: list[dict] | None = None):
        self.uid, self.title, self.description = uid, title, description
        self.variables = variables or []
        self.panels: list[dict] = []
        self.x = self.y = 0
        self.row_h = 0

    def row(self, title: str) -> None:
        self._newline()
        self.panels.append({"type": "row", "title": title, "collapsed": False, "id": len(self.panels) + 1,
                            "gridPos": {"x": 0, "y": self.y, "w": 24, "h": 1}, "panels": []})
        self.y += 1

    def _newline(self) -> None:
        if self.x:
            self.y += self.row_h
            self.x = self.row_h = 0

    def panel(self, title: str, targets: list[tuple[str, str]], *, w: int = 12, h: int = 8, kind: str = "timeseries",
              unit: str = "short", description: str = "", thresholds: list[tuple[float, str]] | None = None,
              datasource: str = "prometheus", extra: dict | None = None) -> None:
        if self.x + w > 24:
            self._newline()
        p = {
            "id": len(self.panels) + 1, "type": kind, "title": title, "description": description,
            "datasource": {"type": "prometheus" if datasource == "prometheus" else datasource, "uid": datasource},
            "gridPos": {"x": self.x, "y": self.y, "w": w, "h": h},
            # Latency panels show exemplars: points linking a histogram bucket to a trace that landed in it (Tempo).
            "targets": [{"refId": chr(65 + i), "expr": e, "legendFormat": legend, "datasource": {"uid": datasource},
                         **({"exemplar": True} if "histogram_quantile" in e else {})}
                        for i, (e, legend) in enumerate(targets)],
            "fieldConfig": {"defaults": {"unit": unit}, "overrides": []},
            "options": {"legend": {"displayMode": "table", "placement": "bottom", "calcs": ["lastNotNull", "max"]}}
            if kind == "timeseries" else {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "background"},
        }
        if thresholds:
            p["fieldConfig"]["defaults"]["thresholds"] = {
                "mode": "absolute", "steps": [{"value": None if i == 0 else v, "color": c} for i, (v, c) in enumerate(thresholds)]}
            if kind == "timeseries":
                p["fieldConfig"]["defaults"]["custom"] = {"thresholdsStyle": {"mode": "line"}}
        if extra:
            p.update(extra)
        self.panels.append(p)
        self.x += w
        self.row_h = max(self.row_h, h)

    def json(self) -> dict:
        return {
            "uid": self.uid, "title": self.title, "description": self.description, "tags": ["e-commerce"],
            "schemaVersion": 41, "version": 1, "editable": False, "refresh": "30s", "timezone": "browser",
            "time": {"from": "now-1h", "to": "now"},
            "templating": {"list": self.variables},
            "links": [{"title": "E-commerce dashboards", "type": "dashboards", "tags": ["e-commerce"], "asDropdown": True}],
            "panels": self.panels,
        }


def query_variable(name: str, query: str, label: str, multi: bool = False) -> dict:
    return {"name": name, "label": label, "type": "query", "datasource": {"type": "prometheus", "uid": "prometheus"},
            "query": {"query": query, "refId": "v"}, "refresh": 2, "sort": 1, "multi": multi, "includeAll": multi,
            **({"current": {"text": "All", "value": "$__all"}} if multi else {})}


def red_dashboard() -> dict:
    d = Dashboard("ecommerce-red", "Services: rate, errors, duration",
                  "RED metrics per service and endpoint (Micrometer http.server.requests).",
                  [query_variable("service", "label_values(http_server_requests_seconds_count, service)", "Service")])
    s = 'service="$service", uri!~"/actuator.*"'
    d.row("Traffic")
    d.panel("Requests per second by endpoint", [(f"sum by (method, uri) (rate(http_server_requests_seconds_count{{{s}}}[$__rate_interval]))", "{{method}} {{uri}}")], unit="reqps")
    d.panel("Responses per second by status", [(f"sum by (status) (rate(http_server_requests_seconds_count{{{s}}}[$__rate_interval]))", "{{status}}")], unit="reqps")
    d.row("Errors")
    d.panel("Server error ratio (5xx)", [(f'sum(rate(http_server_requests_seconds_count{{{s}, outcome="SERVER_ERROR"}}[$__rate_interval])) / sum(rate(http_server_requests_seconds_count{{{s}}}[$__rate_interval]))', "5xx")],
            unit="percentunit", thresholds=[(0, "green"), (0.001, "orange"), (0.01, "red")])
    d.panel("Client errors by status (4xx)", [(f'sum by (status, uri) (rate(http_server_requests_seconds_count{{{s}, outcome="CLIENT_ERROR"}}[$__rate_interval]))', "{{status}} {{uri}}")], unit="reqps")
    d.row("Duration")
    d.panel("p99 by endpoint", [(f"histogram_quantile(0.99, sum by (le, method, uri) (rate(http_server_requests_seconds_bucket{{{s}}}[$__rate_interval])))", "{{method}} {{uri}}")], unit="s")
    d.panel("p50 by endpoint", [(f"histogram_quantile(0.5, sum by (le, method, uri) (rate(http_server_requests_seconds_bucket{{{s}}}[$__rate_interval])))", "{{method}} {{uri}}")], unit="s")
    d.row("JVM")
    d.panel("Heap used", [('sum by (instance) (jvm_memory_used_bytes{service="$service", area="heap"})', "{{instance}}")], w=8, unit="bytes")
    d.panel("GC pause time per second", [('sum by (instance) (rate(jvm_gc_pause_seconds_sum{service="$service"}[$__rate_interval]))', "{{instance}}")], w=8, unit="s")
    d.panel("Process CPU", [('sum by (instance) (process_cpu_usage{service="$service"})', "{{instance}}")], w=8, unit="percentunit")
    return d.json()


def slo_dashboard() -> dict:
    d = Dashboard("ecommerce-slo", "SLOs and error budgets",
                  "SPEC 2.2 N1 and N2, measured per route at the API gateway. Budget left is over the last 30 days.")
    d.row("Error budget left (30 days)")
    for name, objective, *_ in SLOS:
        budget = 1 - objective
        d.panel(name, [(f'1 - avg_over_time(slo:error_ratio:rate5m{{slo="{name}"}}[30d]) / {budget:g}', "budget left")],
                kind="stat", w=24 // 4 if len(SLOS) > 4 else 6, h=4, unit="percentunit",
                thresholds=[(0, "red"), (0.25, "orange"), (0.5, "green")],
                description=f"Objective {objective:.2%}. 100% means no budget spent; below 0 the SLO is missed.")
    d.row("Burn rate (1 = spending the budget exactly over 30 days)")
    for name, objective, *_ in SLOS:
        budget = 1 - objective
        d.panel(name, [(f'slo:error_ratio:rate1h{{slo="{name}"}} / {budget:g}', "1 h"),
                       (f'slo:error_ratio:rate6h{{slo="{name}"}} / {budget:g}', "6 h"),
                       (f'slo:error_ratio:rate5m{{slo="{name}"}} / {budget:g}', "5 min")],
                w=8, unit="short", thresholds=[(0, "green"), (6, "orange"), (14.4, "red")])
    d.row("Latency at the gateway (p99 per route)")
    d.panel("p99 by route", [(f"histogram_quantile(0.99, sum by (le, routeId) (rate({METRIC}_bucket[$__rate_interval])))", "{{routeId}}")], w=24, unit="s",
            description="Budgets: product page 150 ms, search 300 ms, add to cart 100 ms, place order 1.5 s.")
    return d.json()


def events_dashboard() -> dict:
    d = Dashboard("ecommerce-events", "Checkout, events and Kafka",
                  "Saga outcomes, domain events, the transactional outboxes, consumer lag and dead-letter queues.")
    d.row("Checkout saga")
    d.panel("Checkouts per minute by outcome", [("sum by (outcome) (rate(checkout_outcomes_total[$__rate_interval])) * 60", "{{outcome}}")], unit="short")
    d.panel("Decline and unknown-payment ratio", [
        ('sum(rate(checkout_outcomes_total{outcome="PAYMENT_DECLINED"}[$__rate_interval])) / sum(rate(checkout_outcomes_total[$__rate_interval]))', "declined"),
        ('sum(rate(checkout_outcomes_total{outcome=~"PAYMENT_UNKNOWN|FAILED|DEPENDENCY_UNAVAILABLE"}[$__rate_interval])) / sum(rate(checkout_outcomes_total[$__rate_interval]))', "unknown, failed or unavailable")],
        unit="percentunit")
    d.panel("Domain events published per minute", [("sum by (type) (rate(outbox_events_published_total[$__rate_interval])) * 60", "{{type}}")], w=24,
            description="order.OrderConfirmed is orders per minute; order.OrderCancelled counts compensations; payment.PaymentFailed declines.")
    d.row("Transactional outboxes")
    d.panel("Oldest unpublished event", [("max by (service) (outbox_oldest_unpublished_age_seconds)", "{{service}}")], unit="s",
            thresholds=[(0, "green"), (10, "orange"), (30, "red")], description="Alert above 30 s (SPEC 8.2): Kafka is rejecting or slow, or the relay is stuck.")
    d.panel("Unpublished events", [("max by (service) (outbox_unpublished)", "{{service}}")], unit="short")
    d.row("Kafka")
    d.panel("Consumer lag by group and topic", [("sum by (consumergroup, topic) (kafka_consumergroup_lag)", "{{consumergroup}} ← {{topic}}")], unit="short")
    d.panel("Messages in dead-letter topics", [('sum by (topic) (kafka_topic_partition_current_offset{topic=~".+\\\\.dlq"} - kafka_topic_partition_oldest_offset{topic=~".+\\\\.dlq"})', "{{topic}}")],
            unit="short", thresholds=[(0, "green"), (1, "red")], description="Anything here failed five times; replay it after fixing the cause (RUNBOOK.md).")
    d.panel("Messages produced per second by topic", [('sum by (topic) (rate(kafka_topic_partition_current_offset{topic!~"__.*"}[$__rate_interval]))', "{{topic}}")], w=24, unit="short",
            description="A topic growing far faster than its events (outbox_events_published_total) means a producer is re-sending.")
    return d.json()


# ── output ────────────────────────────────────────────────────────────────────────────────────────────────────────────

HEADER = "# Generated by deploy/observability/generate.py from its SLO table; edit that, then run it.\n"


def to_yaml(value, indent: int = 0) -> str:
    """Minimal block YAML for the rule files: dicts, lists, strings (multi-line as literal blocks), numbers."""
    pad = "  " * indent
    if isinstance(value, dict):
        out = []
        for k, v in value.items():
            if isinstance(v, (dict, list)) and v:
                out.append(f"{pad}{k}:\n{to_yaml(v, indent + 1)}")
            else:
                out.append(f"{pad}{k}: {scalar(v, indent + 1)}")
        return "\n".join(out)
    if isinstance(value, list):
        out = []
        for item in value:
            body = to_yaml(item, indent + 1)
            out.append(f"{pad}- {body.lstrip()}")
        return "\n".join(out)
    return pad + scalar(value, indent)


def scalar(v, indent: int) -> str:
    if isinstance(v, str):
        if "\n" in v:
            pad = "  " * indent
            return "|-\n" + "\n".join(pad + line for line in v.split("\n"))
        return json.dumps(v)
    if isinstance(v, (dict, list)):
        return json.dumps(v)
    return json.dumps(v)


def outputs() -> dict[Path, str]:
    rules = slo_rules()
    platform = (ROOT / "prometheus/rules/platform.rules.yml").read_text()
    files = {ROOT / "prometheus/rules/slo.rules.yml": HEADER + to_yaml(rules) + "\n"}
    k8s = {
        "apiVersion": "monitoring.coreos.com/v1", "kind": "PrometheusRule",
        "metadata": {"name": "e-commerce-slo", "namespace": "e-commerce-platform", "labels": {"release": "kube-prometheus-stack"}},
        "spec": rules,
    }
    platform_body = "\n".join("  " + line if line else line for line in platform.split("\n") if not line.startswith("#"))
    files[ROOT / "k8s/prometheusrule.yaml"] = (
        HEADER + "# The SLO rules and prometheus/rules/platform.rules.yml, for the Prometheus Operator.\n"
        + to_yaml(k8s) + "\n---\n"
        + to_yaml({"apiVersion": "monitoring.coreos.com/v1", "kind": "PrometheusRule",
                   "metadata": {"name": "e-commerce-platform", "namespace": "e-commerce-platform", "labels": {"release": "kube-prometheus-stack"}}})
        + "\nspec:\n" + platform_body.rstrip() + "\n")
    for build in (red_dashboard, slo_dashboard, events_dashboard):
        board = build()
        files[ROOT / f"grafana/dashboards/{board['uid']}.json"] = json.dumps(board, indent=2) + "\n"
    return files


def main() -> int:
    check = "--check" in sys.argv[1:]
    stale = []
    for path, content in outputs().items():
        if path.exists() and path.read_text() == content:
            continue
        if check:
            stale.append(path.relative_to(ROOT))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            print(f"wrote {path.relative_to(ROOT)}")
    if stale:
        print("out of date (run deploy/observability/generate.py):", *stale, sep="\n  ")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
