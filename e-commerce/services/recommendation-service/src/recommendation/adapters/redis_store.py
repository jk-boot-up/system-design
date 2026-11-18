"""Co-purchase counts in Redis sorted sets: reco:also:<sku> holds otherSku -> times bought together."""

from __future__ import annotations

from redis.asyncio import Redis

from ..domain import Purchase, Recommendation

SEEN_TTL_SECONDS = 14 * 24 * 3600  # twice Kafka's retention: no duplicate can arrive later
TOP_KEPT = 200  # each list keeps its 200 strongest partners; the long tail is never shown

# One script, so marking the event as seen and counting its pairs happen together or not at all: a crash can neither
# count an order twice nor mark it seen without counting it. KEYS[1] = seen key, KEYS[2..] = reco:also:<sku>;
# ARGV[1] = seen TTL, ARGV[2] = how many partners to keep, ARGV[3..] = the SKUs in the same order as KEYS[2..].
RECORD = """
if not redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1]) then return 0 end
local keep = tonumber(ARGV[2])
for i = 2, #KEYS do
  for j = 2, #KEYS do
    if i ~= j then redis.call('ZINCRBY', KEYS[i], 1, ARGV[j + 1]) end
  end
  redis.call('ZREMRANGEBYRANK', KEYS[i], 0, -(keep + 1))
end
return 1
"""


def also_key(sku: str) -> str:
    return f"reco:also:{sku}"


class RedisCoPurchaseStore:
    def __init__(self, redis: Redis):
        self._redis = redis
        self._record = redis.register_script(RECORD)

    async def record(self, purchase: Purchase) -> bool:
        keys = [f"reco:seen:{purchase.event_id}", *(also_key(s) for s in purchase.skus)]
        return await self._record(keys=keys, args=[SEEN_TTL_SECONDS, TOP_KEPT, *purchase.skus]) == 1

    async def top(self, sku: str, limit: int) -> list[Recommendation]:
        rows = await self._redis.zrange(also_key(sku), 0, limit - 1, desc=True, withscores=True)
        return [Recommendation(sku=(m.decode() if isinstance(m, bytes) else m), score=int(s)) for m, s in rows]
