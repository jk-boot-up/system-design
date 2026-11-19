# Performance tests

Gatling simulations (Java DSL) that hold the system to the latency budgets of [SPEC §2.2 N2](../../design/SPEC.md):
p99 on the server side of the edge, measured from a load generator next to it.

| Simulation | Visitor does | Budget (p99) | Local rate | Target rate (`PERF_LOAD=target`) |
|---|---|---|---|---|
| `BrowseSimulation` | category tree, then three product pages | product page ≤ 150 ms | 8 visitors/s | 300 visitors/s |
| `SearchSimulation` | a search, then the same narrowed by brand and sorted by price | search ≤ 300 ms | 4 visitors/s | 150 visitors/s |
| `CartSimulation` | a guest adds two products, one again, views the cart | add to cart ≤ 100 ms | 6 visitors/s | 200 visitors/s |
| `CheckoutSimulation` | a signed-in shopper adds a product and places the order (the whole saga) | place order ≤ 1.5 s | 1 order/s | 50 orders/s |

Every run also fails if more than 0.1 % of requests fail. The target rates are SPEC §2.2 N3's steady state (about 2,000
requests a second); the flash-sale peak of 500 orders a second is a separate test planned for a production-like cluster.

## How a run works

1. **Seed** (`Seed.java`): through the public API, staff creates a category and 50 products with plenty of stock, all with
   SKUs unique to the run; checkout also creates shopper accounts (`perf-shopper-001…`, kept between runs) and signs
   them in. Search waits until this run's products are indexed.
2. **Warm up** (`Phases.java`): the same scenario at half rate for 30 s. Its requests are named `warm-up: …` and held to no
   budget, so freshly started JVMs compile their hot paths and caches fill first, as they long have in production pods.
3. **Measure:** ramp to the rate over 20 s, hold it for 60 s. Budgets apply to these requests.
4. **Compare** (`scripts/regression.py`): each request's p99 against the baseline of this environment
   (`baseline/<PERF_BASELINE>/`, default `local`). More than 10 % and more than 5 ms slower fails (SPEC §13).

## Run against the local stack

```bash
./run-compose.sh                       # all four, then the regression check (about 12 minutes)
./run-compose.sh Browse Cart           # only these
UPDATE_BASELINE=1 ./run-compose.sh     # accept this run as the new local baseline
```

`run-compose.sh` starts the stack with [`compose.performance.yaml`](compose.performance.yaml). Gatling sends everything
from one address, while real visitors come from many, so the overlay lifts the **per-address** limits (edge 50 r/s, gateway
20 r/s per anonymous address); otherwise the test would measure the limiter. The per-user checkout limit and per-SKU
admission control stay on, which is why every checkout visitor has an account of its own. For Search the runner stops
order, payment and cart while OpenSearch runs, so it fits in a 4 GB Docker VM.

One simulation by hand: `./gradlew gatlingRun --simulation com.jkbootup.ecommerce.performance.BrowseSimulation`.
Reports: `build/reports/gatling/<simulation>-<time>/index.html`.

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `PERF_LOAD` | `local` | `local` or `target` rates (table above) |
| `PERF_WARM_UP_SECONDS`, `PERF_RAMP_SECONDS`, `PERF_STEADY_SECONDS` | 30, 20, 60 | Phase lengths; together under 14 minutes (shopper tokens last 15) |
| `PERF_EDGE` | `https://localhost:8443` | The edge |
| `PERF_KEYCLOAK`, `PERF_REALM`, `PERF_CLIENT_ID` | local Keycloak, `shop`, `shop-web` | Accounts and tokens (password grant: dev realm only) |
| `PERF_KEYCLOAK_ADMIN`, `PERF_KEYCLOAK_ADMIN_PASSWORD` | `admin`, `admin` | Creates the shopper accounts |
| `PERF_STAFF` | `sam` | Staff user that seeds the catalogue |
| `PERF_SHOPPERS`, `PERF_PRODUCTS` | 10 × order rate (at least 20), 50 | Accounts and products |
| `PERF_INSECURE_TLS` | `true` | Accept the local self-signed certificate |
| `PERF_BASELINE` | `local` | Baseline set for the regression check |

## Results on this machine

Apple silicon, the whole system in a 4 GB Docker VM, local rates, after warm-up (`baseline/local/`):

| Request | p99 | Budget |
|---|---|---|
| product page | 9 ms | 150 ms |
| add to cart | 54 ms | 100 ms |
| place order | 320 ms | 1,500 ms |
| search | not yet measured: in the local run this run's products were not searchable within 90 s (under investigation) | 300 ms |

These numbers show the budgets are reachable and catch regressions on one machine; they say nothing about capacity. The
target rates belong on a production-like cluster (`PERF_LOAD=target`, the k8s staging overlay, several load generators).

CI: [e-commerce-performance.yml](../../../.github/workflows/e-commerce-performance.yml) runs nightly and compares with the last
accepted run on the same runner type (kept in the Actions cache); a manual run can accept a slower result as the new baseline.
