"""The two use cases, Learn and Recommend, written against ports."""

from __future__ import annotations

from enum import StrEnum
from typing import Protocol

from .domain import BadEvent, Purchase, Recommendation, parse_event


class CoPurchaseStore(Protocol):
    async def record(self, purchase: Purchase) -> bool:
        """Count every pair of the purchase, once per event: False if this event was already counted."""
        ...

    async def top(self, sku: str, limit: int) -> list[Recommendation]: ...


class DeadEvents(Protocol):
    async def dead(self, value: bytes, reason: str) -> None: ...


class Learned(StrEnum):
    COUNTED = "counted"
    DUPLICATE = "duplicate"
    IGNORED = "ignored"
    DEAD_LETTER = "dead_letter"


async def learn(value: bytes, store: CoPurchaseStore, dead: DeadEvents) -> Learned:
    """Store errors propagate: the caller retries and does not commit the offset."""
    try:
        purchase = parse_event(value)
    except BadEvent as e:
        await dead.dead(value, str(e))
        return Learned.DEAD_LETTER
    if purchase is None:
        return Learned.IGNORED
    return Learned.COUNTED if await store.record(purchase) else Learned.DUPLICATE


MAX_LIMIT = 50


async def recommend(sku: str, limit: int, store: CoPurchaseStore) -> list[Recommendation]:
    if not 1 <= limit <= MAX_LIMIT:
        raise ValueError(f"limit must be between 1 and {MAX_LIMIT}")
    return await store.top(sku, limit)
