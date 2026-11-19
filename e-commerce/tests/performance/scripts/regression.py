#!/usr/bin/env python3
"""p99 regression check (SPEC §13: a p99 more than 10 % worse than the baseline fails the pipeline).

Reads each simulation's newest Gatling report (build/reports/gatling/<simulation>-<timestamp>/index.html), takes every
request's 99th percentile from the statistics table (columns found by their headings, not their position), and compares
it with baseline/<environment>/<simulation>.json. Small numbers are noisy, so a request regresses only when it is both
more than 10 % and more than 5 ms slower.

  scripts/regression.py                    compare every simulation that has a report
  scripts/regression.py --update           also make these results the new baseline (after a run you accept)
  PERF_BASELINE=ci scripts/regression.py   baselines per environment: "local" (default), "ci", a cluster's name

Standard library only.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPORTS = ROOT / "build" / "reports" / "gatling"
TOLERANCE = 0.10
NOISE_MS = 5
WARM_UP = "warm-up: "   # the warm-up phase (Phases.java) is held to nothing


class StatsTable(HTMLParser):
    """Collects the header cells and request rows of Gatling's statistics table."""

    def __init__(self) -> None:
        super().__init__()
        self.headers: dict[str, str] = {}   # column id (col-N) -> heading
        self.rows: list[dict[str, str]] = []
        self._header_col: str | None = None
        self._cell_col: str | None = None
        self._row: dict[str, str] | None = None
        self._in_name = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        a = dict(attrs)
        classes = (a.get("class") or "").split()
        if tag == "th" and (a.get("id") or "").startswith("col-"):
            self._header_col = a["id"]
            self.headers[self._header_col] = ""
        elif tag == "tr" and (a.get("id") or "").startswith("req_"):
            self._row = {}
            self.rows.append(self._row)
        elif tag == "td" and self._row is not None:
            self._cell_col = next((c for c in classes if c.startswith("col-")), None)
            if self._cell_col and self._cell_col != "col-1":
                self._row[self._cell_col] = ""
        elif tag == "span" and "ellipsed-name" in classes and self._row is not None:
            self._in_name = True

    def handle_endtag(self, tag: str) -> None:
        if tag == "th":
            self._header_col = None
        elif tag == "td":
            self._cell_col = None
        elif tag == "span":
            self._in_name = False
        elif tag == "tr":
            self._row = None

    def handle_data(self, data: str) -> None:
        if self._header_col:
            self.headers[self._header_col] += data.strip()
        elif self._in_name and self._row is not None:
            self._row["name"] = self._row.get("name", "") + data.strip()
        elif self._row is not None and self._cell_col and self._cell_col != "col-1":
            self._row[self._cell_col] += data.strip()


def p99s(report: Path) -> dict[str, int]:
    table = StatsTable()
    table.feed(report.read_text(encoding="utf-8"))
    col = next((c for c, h in table.headers.items() if h == "99th pct"), None)
    if col is None:
        sys.exit(f"{report}: no '99th pct' column; has the Gatling report format changed?")
    return {row["name"]: int(float(row[col])) for row in table.rows
            if row.get("name") and not row["name"].startswith(WARM_UP) and row.get(col, "-") not in ("", "-")}


def newest_reports() -> dict[str, Path]:
    newest: dict[str, Path] = {}
    for d in sorted(REPORTS.glob("*-*")):   # timestamps sort lexically
        if (d / "index.html").exists():
            newest[d.name.rsplit("-", 1)[0]] = d / "index.html"
    return newest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--update", action="store_true", help="write these results as the new baseline, accepting any regression")
    args = parser.parse_args()

    baseline_dir = ROOT / "baseline" / os.environ.get("PERF_BASELINE", "local")
    reports = newest_reports()
    if not reports:
        sys.exit(f"no Gatling reports under {REPORTS}; run a simulation first")

    failed = False
    for simulation, report in sorted(reports.items()):
        current = p99s(report)
        baseline_file = baseline_dir / f"{simulation}.json"
        baseline = json.loads(baseline_file.read_text()) if baseline_file.exists() else {}
        print(f"{simulation} ({report.parent.name})")
        for name, p99 in sorted(current.items()):
            before = baseline.get(name)
            if before is None:
                print(f"  {name:<24} p99 {p99:>6} ms   (no baseline)")
                continue
            limit = max(before * (1 + TOLERANCE), before + NOISE_MS)
            worse = p99 > limit
            failed |= worse
            change = (p99 - before) / before * 100 if before else 0.0
            print(f"  {name:<24} p99 {p99:>6} ms   baseline {before:>6} ms   {change:+6.1f} %   {'REGRESSED' if worse else 'ok'}")
        if args.update:
            baseline_dir.mkdir(parents=True, exist_ok=True)
            baseline_file.write_text(json.dumps(current, indent=2, sort_keys=True) + "\n")
            print(f"  baseline written: {baseline_file.relative_to(ROOT)}")
    if failed:
        print(f"p99 regressed by more than {TOLERANCE:.0%} (and {NOISE_MS} ms) against {baseline_dir.relative_to(ROOT)}")
        if args.update:
            print("accepted as the new baseline (--update)")
            return 0
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
