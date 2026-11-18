import pytest

from recommendation.app import Learned, learn, recommend
from tests.events import confirmed
from tests.fakes import FakeDead, FakeStore


async def test_counts_once_per_event_and_ranks_by_count():
    store, dead = FakeStore(), FakeDead()
    assert await learn(confirmed("e1", "TENT", "STOVE", "MUG"), store, dead) == Learned.COUNTED
    assert await learn(confirmed("e1", "TENT", "STOVE", "MUG"), store, dead) == Learned.DUPLICATE
    assert await learn(confirmed("e2", "TENT", "STOVE"), store, dead) == Learned.COUNTED
    top = await recommend("TENT", 10, store)
    assert [(r.sku, r.score) for r in top] == [("STOVE", 2), ("MUG", 1)]


async def test_bad_events_are_dead_lettered_and_store_errors_propagate():
    store, dead = FakeStore(), FakeDead()
    assert await learn(b"garbage", store, dead) == Learned.DEAD_LETTER
    assert len(dead.values) == 1
    store.down = True
    with pytest.raises(ConnectionError):
        await learn(confirmed("e3", "A1", "B1"), store, dead)


async def test_limit_is_bounded():
    with pytest.raises(ValueError):
        await recommend("TENT", 0, FakeStore())
