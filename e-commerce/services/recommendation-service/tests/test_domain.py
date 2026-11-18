import pytest

from recommendation.domain import MAX_SKUS_PER_ORDER, BadEvent, pairs, parse_event
from tests.events import confirmed


def test_confirmed_order_gives_distinct_skus_and_all_pairs():
    p = parse_event(confirmed("e1", "SKU-1", "SKU-2", "SKU-1", "SKU-3"))
    assert p.skus == ("SKU-1", "SKU-2", "SKU-3")
    assert len(pairs(p)) == 6
    assert ("SKU-1", "SKU-2") in pairs(p) and ("SKU-2", "SKU-1") in pairs(p)


def test_single_product_orders_and_other_events_teach_nothing():
    assert parse_event(confirmed("e1", "SKU-1", "SKU-1")) is None
    assert parse_event(b'{"eventId":"e2","type":"order.OrderCancelled","payload":{}}') is None


@pytest.mark.parametrize("value", [b"not json", b'{"type":"order.OrderConfirmed"}', b"[1,2]",
                                   b'{"eventId":"e","type":"order.OrderConfirmed","payload":{"lines":[]}}',
                                   b'{"eventId":"e","type":"order.OrderConfirmed","payload":{"lines":[{"sku":"bad sku"}]}}'])
def test_broken_events_are_bad(value):
    with pytest.raises(BadEvent):
        parse_event(value)


def test_huge_orders_are_capped():
    p = parse_event(confirmed("e1", *(f"SKU-{i}" for i in range(80))))
    assert len(p.skus) == MAX_SKUS_PER_ORDER
