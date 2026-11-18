from collections import defaultdict

from recommendation.domain import Purchase, Recommendation, pairs


class FakeStore:
    def __init__(self):
        self.seen: set[str] = set()
        self.counts: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
        self.down = False

    async def record(self, purchase: Purchase) -> bool:
        if self.down:
            raise ConnectionError("redis down")
        if purchase.event_id in self.seen:
            return False
        self.seen.add(purchase.event_id)
        for a, b in pairs(purchase):
            self.counts[a][b] += 1
        return True

    async def top(self, sku: str, limit: int) -> list[Recommendation]:
        if self.down:
            from redis.exceptions import ConnectionError as RedisDown

            raise RedisDown("redis down")
        # Same order as Redis: highest count first, ties by member descending.
        ranked = sorted(self.counts[sku].items(), key=lambda kv: (kv[1], kv[0]), reverse=True)
        return [Recommendation(s, c) for s, c in ranked[:limit]]


class FakeDead:
    def __init__(self):
        self.values: list[tuple[bytes, str]] = []

    async def dead(self, value: bytes, reason: str) -> None:
        self.values.append((value, reason))
