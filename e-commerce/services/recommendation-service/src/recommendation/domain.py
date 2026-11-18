"""Rules with no I/O: which events teach us something, and what a recommendation request may ask for."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass

# Orders with more distinct products than this are cut down to the first ones: pairs grow with the square of the count,
# and very large orders (a shop buying stock) say little about what shoppers buy together.
MAX_SKUS_PER_ORDER = 50
SKU = re.compile(r"^[A-Z0-9][A-Z0-9-]{1,63}$")


class BadEvent(ValueError):
    """An event that can never be learned from: it goes to the dead-letter topic."""


@dataclass(frozen=True)
class Purchase:
    event_id: str
    skus: tuple[str, ...]  # distinct, in order of first appearance


def parse_event(value: bytes) -> Purchase | None:
    """A Purchase for OrderConfirmed with at least two distinct products; None for anything that teaches nothing."""
    try:
        event = json.loads(value)
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        raise BadEvent(f"not JSON: {e}") from e
    if not isinstance(event, dict) or not event.get("eventId") or not event.get("type"):
        raise BadEvent("eventId and type are required")
    if event["type"] != "order.OrderConfirmed":
        return None
    lines = (event.get("payload") or {}).get("lines")
    if not isinstance(lines, list) or not lines:
        raise BadEvent(f"OrderConfirmed {event['eventId']} has no lines")
    skus: list[str] = []
    for line in lines:
        sku = line.get("sku") if isinstance(line, dict) else None
        if not isinstance(sku, str) or not SKU.match(sku):
            raise BadEvent(f"OrderConfirmed {event['eventId']} has a bad SKU {sku!r}")
        if sku not in skus:
            skus.append(sku)
    if len(skus) < 2:
        return None
    return Purchase(event_id=str(event["eventId"]), skus=tuple(skus[:MAX_SKUS_PER_ORDER]))


def pairs(purchase: Purchase) -> list[tuple[str, str]]:
    """Every ordered pair (a, b) with a != b: b goes up in a's list and a in b's."""
    return [(a, b) for a in purchase.skus for b in purchase.skus if a != b]


@dataclass(frozen=True)
class Recommendation:
    sku: str
    score: int
