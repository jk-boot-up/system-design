# storefront

The shop's web front end: pages rendered on the server by React Router 8 (framework mode) on Node 26, which also acts as
the **backend-for-frontend**. It signs shoppers in with Keycloak as a confidential client, keeps their tokens in encrypted
server-side sessions, and calls the API gateway for them. **No token ever reaches the browser.**

| | |
|---|---|
| Language / framework | TypeScript 5.9 (strict), React 19, React Router 8.5 framework mode, Vite 8, Tailwind CSS 4, Express 5 |
| Runtime | Node 26 LTS, which runs `server.ts` directly (type stripping); distroless image, non-root, read-only root |
| Store | `redis-session`: sessions sealed with AES-256-GCM (key ring, session id as associated data), keys are SHA-256 of the id; 30 min idle, 12 h absolute |
| Calls | api-gateway (typed clients generated from each service's `contracts/openapi.yaml`), Keycloak (OIDC with PKCE), object storage (staff image upload) |
| Called by | the edge only (pages, `/assets`, sign-in) and Keycloak (back-channel logout) |
| Port | 8090 (`/healthz`, `/readyz`, `/metrics` are not routed by the edge) |
| Design | [design/16 Storefront UI](../../design/16-storefront-ui.md) · [ADR 0012](../../design/adr/0012-storefront-server-rendered-with-react-router-and-a-backend-for-frontend.md) |

## Layout

```
storefront/
├─ server.ts                 Express: headers, limits, probes, metrics, static assets, graceful drain
├─ app/
│  ├─ root.tsx               layout, header, root loader (user, CSRF token, categories, cart count), error page
│  ├─ entry.server.tsx       streaming render, CSP with a per-page nonce, error logging
│  ├─ routes.ts              the route table
│  ├─ routes/                home, category, product, search, cart, checkout, orders, order, review, not-found
│  │  ├─ auth/               login, callback, logout, back-channel logout
│  │  └─ staff/              products (new, edit with If-Match), image upload, categories, stock
│  ├─ .server/               server-only: config (zod), crypto, session store, OIDC, gateway clients, shop middleware
│  │                         (session, CSRF, token refresh, cache headers), checkout idempotency, staff guard
│  ├─ api/                   generated OpenAPI types (npm run api:types)
│  ├─ components/, lib/      product cards, notices, money, images, problem messages
├─ test/                     Vitest unit tests
├─ scripts/                  API type generation, JavaScript budget
├─ k8s/                      Deployment, Service, HPA, PDB, NetworkPolicy, ConfigMap
└─ Dockerfile, lighthouserc.json
```

## Security, in short

* **Sessions:** `__Host-sid` cookie (HttpOnly, Secure, SameSite=Lax), rotated at sign-in and when roles change; the server
  refreshes the access token a minute before it expires (one refresh per session, under a Redis lock).
* **CSRF:** every POST must come from our origin (`Origin`, or `Sec-Fetch-Site` when it is missing), and signed-in forms carry
  a per-session token.
* **Headers:** strict CSP with a per-page nonce (no inline script without it, no `eval`), HSTS preload, `X-Frame-Options:
  DENY`, COOP, CORP, Permissions-Policy. Pages that depend on who is asking are `private, no-store`.
* **Checkout:** the server owns the `Idempotency-Key`; a double click, reload or retry can never place two orders.
* **Staff pages:** the `staff` role and a second factor (`amr`) are required; dev turns the second factor off because its
  test users have none.
* **Errors:** shoppers see a message from one table and a reference id; upstream detail stays in the server log.

## Run

With the compose stack running (see [deploy/compose](../../deploy/compose/README.md)), the storefront is at
`https://localhost:8443/`. Sign in as `ada` / `bob` (shoppers) or `sam` (staff); passwords are in the realm file.

To work on it with hot reload against the same stack:

```bash
npm ci
cp .env.example .env        # points at the compose stack; PUBLIC_ORIGIN=http://localhost:5173
npm run dev                 # http://localhost:5173
```

## Check

```bash
npm run api:types           # regenerate API types after a contract change (CI fails if they are stale)
npm run typecheck && npm run lint && npm test
npm run build && npm run budget
docker build -t e-commerce/storefront:0.1.0 .
```

The browser journeys (with axe on every page) are in [tests/e2e](../../tests/e2e/README.md) (`journeys/storefront.spec.ts`).
CI: [e-commerce-storefront.yml](../../../.github/workflows/e-commerce-storefront.yml) on every change (audit, contract types,
typecheck, lint, tests, build, budget, SBOM, image), and the nightly end-to-end workflow adds an OWASP ZAP baseline scan and
Lighthouse budgets.

## Configuration

| Variable | Meaning |
|---|---|
| `PUBLIC_ORIGIN` | The shop's public origin; used for redirects and the CSRF origin check |
| `GATEWAY_URL` | api-gateway inside the cluster |
| `OIDC_ISSUER`, `OIDC_BACKCHANNEL_ORIGIN` | Keycloak's public issuer, and the in-cluster address used to reach it |
| `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET` | the `shop-storefront` confidential client (secret from the vault) |
| `REDIS_URL` | `redis-session` |
| `SESSION_ENCRYPTION_KEYS` | `id:base64key,...`, newest first; older keys only decrypt (rotation without signing anyone out) |
| `IMAGE_BASE_URL`, `OBJECT_STORAGE_INTERNAL_ORIGIN` | public image base; in-cluster object storage address for uploads |
| `STAFF_REQUIRE_SECOND_FACTOR` | `true` unless set to `false` (dev only) |
