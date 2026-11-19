# End-to-end tests

Journeys across the running system, through the public NGINX edge as a client would use it, checking what happens
afterwards in every service (stock, cart, email, recommendations, search, analytics). Milestone P3 of the
[implementation plan](../../design/IMPLEMENTATION-PLAN.md).

Written in TypeScript with Playwright Test, the same toolchain the [storefront](../../design/16-storefront-ui.md) will use, so
its browser journeys join this suite.

```bash
cd e-commerce/tests/e2e
npm ci
./run-compose.sh                      # starts the compose stack and runs everything, one profile at a time (fits 4 GB)
./run-compose.sh core async           # only these
npm run core                          # core journeys against a stack that is already running
npx playwright test --grep @search    # one profile's journeys against a running stack
npx playwright show-report            # last run's HTML report
```

## Journeys

| File | Profile | What it proves |
|---|---|---|
| `browse.spec.ts` | core | A product staff publishes is readable by anyone and micro-cached at the edge (MISS → HIT); unknown products are `404 NOT_FOUND` problems; only staff change the catalogue (`401 UNAUTHENTICATED`, `403 FORBIDDEN`); `/internal` is not reachable |
| `cart.spec.ts` | core | A guest cart (`X-Guest-Id`) merges into the account cart at sign-in with correct totals; quantity 0 removes a line; quantities above 99 are `400 VALIDATION_FAILED` |
| `checkout.spec.ts` | core | Confirmed order takes stock and empties the cart; the same `Idempotency-Key` returns the same order and takes no more stock; out of stock (`409`), declined card (`402`, stock released, cart kept), unknown payment (`504 PAYMENT_UNKNOWN`, stock released), empty cart (`422`); another customer cannot read the order |
| `cancel.spec.ts` | core | Cancelling a confirmed order refunds it and returns the stock; cancelling again is safe (idempotent) |
| `search.spec.ts` | `search` | A new product becomes searchable with brand facets; when it sells out it leaves in-stock results |
| `reviews.spec.ts` | `reviews` | A buyer's review is verified, a non-buyer's is not; one review per customer (`409 ALREADY_REVIEWED`); the rating summary is right |
| `async.spec.ts` | `async` | An order sends exactly one confirmation email (checked in Mailpit) and makes the other items recommendations |
| `analytics.spec.ts` | `analytics` | The confirmed order lands once in ClickHouse with a hashed customer key; the dashboard user is read-only |

Every error response is checked to be an RFC 9457 problem with the catalogue's `code` and a `traceId`.

## How the tests are built

* **Fresh data per test.** Staff creates new SKUs for each journey, so runs never depend on each other or on old data.
* **Waiting for events, not sleeping.** Effects that travel through Kafka are polled with a deadline (`eventually`).
* **Rate limits respected.** Checkout is limited to three orders and then one every five seconds per user; a `429` is waited
  out with `Retry-After` and the same `Idempotency-Key`, as a real client must.
* **Read-only side doors** for facts the public API does not show: inventory's internal stock endpoint, Mailpit's API,
  review-service's purchase table (to know when the verified badge can be expected), and ClickHouse as the read-only
  dashboard user.
* **Profiles.** A journey that needs an optional profile skips when it is not running, unless `E2E_REQUIRE` names it (or
  `all`): then it fails. `run-compose.sh` always sets it for the profile it starts.

## Settings

| Variable | Default (local compose stack) |
|---|---|
| `E2E_EDGE` | `https://localhost:8443` |
| `E2E_TOKEN_URL`, `E2E_CLIENT_ID` | Keycloak at `localhost:8180`, client `shop-web` (password grant: dev realm only) |
| `E2E_INSECURE_TLS` | `true` (self-signed certificate) |
| `E2E_INVENTORY`, `E2E_MAILPIT`, `E2E_CASSANDRA`, `E2E_CLICKHOUSE` | the compose ports |
| `E2E_REQUIRE` | empty; e.g. `search,async` or `all` |

## Memory

The whole stack needs about 10 GB. `run-compose.sh` runs the core stack plus one profile at a time and stops core services a
profile does not use (search stops checkout). On a 4 GB Docker VM the `reviews` profile, which needs the full checkout
path *and* Cassandra, only fits when nothing else runs in Docker; give Docker 6 GB or more to run it. CI runs everything
nightly ([workflow](../../../.github/workflows/e-commerce-e2e.yml)).
