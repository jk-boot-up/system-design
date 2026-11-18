from fastapi.testclient import TestClient

from recommendation.adapters.http import Metrics, create_app
from recommendation.app import learn
from tests.events import confirmed
from tests.fakes import FakeDead, FakeStore


async def test_api():
    store = FakeStore()
    await learn(confirmed("e1", "TENT", "STOVE"), store, FakeDead())
    client = TestClient(create_app(store, Metrics()))
    r = client.get("/api/v1/products/TENT/recommendations?limit=5")
    assert r.status_code == 200
    assert r.json() == {"sku": "TENT", "items": [{"sku": "STOVE", "score": 1}]}
    assert r.headers["cache-control"] == "public, max-age=300"
    assert client.get("/api/v1/products/NOTHING/recommendations").json()["items"] == []
    assert client.get("/api/v1/products/TENT/recommendations?limit=500").json()["code"] == "INVALID_LIMIT"
    assert client.get("/api/v1/products/bad sku/recommendations").json()["code"] == "INVALID_SKU"
    store.down = True
    down = client.get("/api/v1/products/TENT/recommendations")
    assert down.status_code == 503 and down.headers["retry-after"] == "5"
    assert down.json()["code"] == "DEPENDENCY_UNAVAILABLE"
    assert down.headers["content-type"] == "application/problem+json"
