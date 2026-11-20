# 0014. Service identity with client-credentials tokens on internal calls

| | |
|---|---|
| Status | Accepted |
| Date | 2026-11-20 |

## Context

SPEC §8.1 asks that services authenticate each other. The internal APIs (`/internal/...`: reserve stock, authorise and
capture payments, read a checkout snapshot, look up prices) were protected only by network policies: any pod that could
reach a port could call it without credentials, and inventory-service and payment-service had no authentication at all.
A single compromised pod in the shop namespace, or a mistake in one policy, would let it capture payments or reserve all
stock. Locally (compose) there are no network policies, so nothing protected these APIs.

The two usual answers are a service mesh with mutual TLS (identities from certificates, policies in the mesh) and
OAuth 2.0 client-credentials tokens checked by each service.

## Decision

**Each calling service has its own confidential client in the realm and sends a client-credentials access token on every
internal call; each called service checks the token.**

* Callers: order-service (→ cart, inventory, payment) and cart-service (→ catalog). Their realm clients allow only the
  client-credentials grant, carry no user or realm roles, and add exactly the callees' names to the token's audience.
* Callees check, per route: a valid signature, issuer and expiry (as for user tokens), **the audience contains the
  callee's own name**, and **the authorised party (`azp`) is on the route's list**. A customer's token fails the audience
  check; another service's token fails the `azp` check. The rule is one small class (`InternalCallers`) per service,
  following ADR 0003 (no shared code).
* inventory's stock routes stay staff-only: the gateway rewrites `PUT /api/v1/admin/stock/{sku}` to `/internal/stock/{sku}`
  and forwards the staff member's token, so they require the `staff` role, not a service token.
* Tokens are cached by the caller (`ServiceTokens`): renewed after two thirds of their 15-minute lifetime by one thread
  while the others keep using the current token; a failed renewal is retried at most every 5 seconds and the current
  token is used until it expires. Keycloak can therefore be unavailable for about five minutes without affecting
  checkout. Without any valid token a call is not sent and fails like an unreachable service, which the circuit breakers
  and degraded modes already handle.
* Services fetch signing keys and tokens from Keycloak inside the cluster (`http://keycloak:8080`), while tokens keep the
  public issuer. The network policies stay as they are: two independent checks.

## Consequences

* A pod that can reach `/internal` without the caller's client secret gets 401 or 403, in Kubernetes and in compose. The
  end-to-end journeys check this from outside the services.
* Keycloak is now on the checkout path for token renewal, bounded by the cache as above. Its availability matters more;
  it already runs two instances.
* Two more secrets (one per calling service) are held in the vault and read by both Keycloak and the service.
* Traffic between pods is still plain HTTP inside the cluster. Tokens are bearer credentials, so someone who can read pod
  traffic could replay one within its 15 minutes, and only towards the services in its audience. Encryption in transit
  inside the cluster (a mesh with mTLS, or the CNI's WireGuard encryption) is recorded as a remaining gap in
  [12 §12.8](../12-security-design.md#128-gaps-designed-not-yet-built).
* Kafka consumers are not covered: events are authorised by topic ACLs in the platform, not by tokens.

## Alternatives considered

* **Service mesh with mTLS only (Istio, Linkerd):** encrypts and authenticates every connection without code, but adds a
  control plane and sidecars or node proxies to operate, does nothing in the local compose stack, and its identities are
  per workload rather than tied to the realm the rest of the system already uses. It remains the plan for encryption in
  transit and can be added under this decision.
* **A shared static API key per callee:** simple, but a key never expires, cannot say who is calling, and has to be
  rotated by hand in two places.
* **Forwarding the customer's token:** order-service's recovery job and Kafka-triggered work have no customer token, and a
  customer's token would let any service act as that customer anywhere.
