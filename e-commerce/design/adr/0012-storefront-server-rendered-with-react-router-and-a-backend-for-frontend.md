# 0012. Storefront server-rendered with React Router and a backend-for-frontend

| | |
|---|---|
| Status | Accepted |
| Date | 2026-11-19 |

## Context

The platform needs a web storefront for shoppers and staff that is production grade and secure: public pages fast on phones
and indexable, no tokens in the browser, every checkout outcome handled. Details and the full ranking:
[16 Storefront UI](../16-storefront-ui.md).

## Decision

Build the storefront with React Router 8 in framework mode (React 19, TypeScript, Vite), rendered on the server by a Node 26
LTS process that is also the backend-for-frontend: it signs users in with Keycloak as a confidential OIDC client, keeps tokens
in encrypted server-side sessions (`redis-session`) and calls the gateway on the browser's behalf. The browser holds only an
`HttpOnly` `__Host-` session cookie. Only server rendering, loaders and actions are used (no React Server Components).

## Alternatives considered

| Option | Why not |
|---|---|
| React + Vite single-page app, tokens in the browser | Any XSS bug could use the tokens; public pages render only after JavaScript runs |
| Same app with a separate BFF service | Two processes to build and run, and public pages still not server-rendered |
| Next.js 16 | Second in the ranking and the most used React framework; more concepts (server components, caches, middleware) and more critical advisories in 2026 in exactly those features |
| SvelteKit, Nuxt | As capable; smaller ecosystems and hiring pools |

## Consequences

* A new Node workload (`storefront`) and a `redis-session` store in every environment.
* Framework and runtime advisories must be patched within days (critical within 72 hours).
* Public pages can be cached at the edge for signed-out visitors; signed-in pages are never cached.
* The components are plain React, so moving to another React framework later reuses them.
