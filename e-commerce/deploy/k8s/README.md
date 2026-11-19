# Kubernetes overlays

The whole e-commerce system on Kubernetes, for three environments, built with Kustomize from each service's own `k8s/`
base. Data stores are custom resources of their operators. Design: [design/03-deployment.md](../../design/03-deployment.md).

```bash
cd e-commerce/deploy/k8s
./verify.sh                                   # build dev, staging and prod
./verify.sh --server                          # and dry-run every object on a kind cluster against the operators' CRDs
kubectl apply --server-side -k overlays/dev   # deploy (operators installed first, below)
```

## Layout

```text
base/
  namespaces.yaml        e-commerce-shop (services, edge, analytics) and e-commerce-platform (data stores, Kafka, Keycloak)
  shop/                  the 11 service bases + NGINX edge, unchanged; default deny; aliases to the platform stores;
                         in-cluster JWK set URIs; RuntimeDefault seccomp; the edge certificate; KEDA credentials
  platform/              PostgreSQL + PgBouncer, Kafka + topics, Redis, OpenSearch, Cassandra, RabbitMQ, Keycloak + realm
components/
  hosts/                 copies an overlay's host names (its environment.yaml) into issuers, CORS, realm, certificates
  single-replica/        dev sizing: one of everything, replication factor 1
  dev-secrets/           fixed dev passwords
  external-secrets/      secrets from the environment's vault (staging, prod)
  local-mail/            Mailpit (dev, staging)
  local-object-storage/  RustFS as the S3 store (dev, staging)
  self-signed-tls/       self-signed ClusterIssuer (dev)
  test-users/            ada, bob (customers) and sam (staff), and the password grant (dev)
overlays/
  dev/  staging/  prod/
```

## Environments

| | dev | staging | prod |
|---|---|---|---|
| Hosts | dev.shop.example, id.dev.… | staging.shop.example, id.staging.… | shop.example, id.shop.example |
| Service replicas | 1 (HPA up to 2) | HPA from 2 | as in each base (HPA from 2, or 3 for gateway, order and inventory) |
| Data stores | 1 replica each, RF 1 | 3 replicas across zones, about half the size | 3 replicas across zones (design §3.1) |
| Secrets | `dev-secrets` (in Git, dev only) | External Secrets, staging vault | External Secrets, production vault |
| Email | Mailpit | Mailpit | SMTP relay `smtp-relay.shop.example:587` |
| Object storage | RustFS | RustFS | provider S3 at `storage.shop.example` (egress gateway) |
| Certificates | self-signed | Let's Encrypt staging | Let's Encrypt |
| Keycloak users | test users, password grant | sign-up only | sign-up only |

## Where things run

* **e-commerce-shop**: every service, the NGINX edge (the only `LoadBalancer`) and the analytics ClickHouse. Default deny;
  each workload's own policy (in its service base) opens what it needs. Short store names in the service settings
  (`kafka-bootstrap`, `pgbouncer-orders`, `redis-cart`, …) are `ExternalName` aliases to the platform namespace, so the bases
  stay the same as in the local stack.
* **e-commerce-platform**: one CloudNativePG cluster per owning service (orders, inventory, payments, catalog) and one for
  Keycloak; PgBouncer poolers in transaction mode for orders, inventory and payments; Kafka in KRaft mode with the topics of
  the event catalogue; Redis per use (`redis-cache`, `redis-ratelimit`, `redis-cart`, `redis-reco`, `redis-notify`);
  OpenSearch; Cassandra (data centre `dc1`); RabbitMQ with notification's own user; Keycloak with the `shop` realm. Ingress
  is allowed from this namespace, the shop namespace, the operators, `monitoring` and `ingress-nginx`.

`redis-notify` is separate from the cache on purpose: an LRU eviction of a sent-log entry would let an email go out twice.

## Operators (once per cluster)

| Operator | Version | Namespace | Install |
|---|---|---|---|
| cert-manager | 1.21.3 | `cert-manager` | Helm chart `jetstack/cert-manager`, `crds.enabled=true` |
| CloudNativePG | 1.30.2 | `cnpg-system` | `kubectl apply --server-side -f https://github.com/cloudnative-pg/cloudnative-pg/releases/download/v1.30.2/cnpg-1.30.2.yaml` |
| Strimzi | 1.3.1 | `strimzi` | Helm chart `strimzi/strimzi-kafka-operator`, `watchNamespaces={e-commerce-platform}` |
| OpsTree Redis operator | 0.27.0 | `redis-operator` | Helm chart `ot-helm/redis-operator` |
| OpenSearch operator | 3.0.0 | `opensearch-operator` | Helm chart `opensearch-operator/opensearch-operator` |
| K8ssandra operator | 1.34.0 | `k8ssandra-operator` | Helm chart `k8ssandra/k8ssandra-operator`, cluster-scoped |
| RabbitMQ cluster + messaging topology operators | 2.23.3, 1.20.6 | `rabbitmq-system` | their release manifests |
| Keycloak operator | 26.8.2 | **`e-commerce-platform`** (it watches only its own namespace) | the `keycloak-k8s-resources` manifests |
| External Secrets Operator | 2.15.0 | `external-secrets` | Helm chart `external-secrets/external-secrets` (staging, prod) |
| KEDA | 2.21.1 | `keda` | Helm chart `kedacore/keda` |
| kube-prometheus-stack (Prometheus Operator 0.94.2, Prometheus, Alertmanager, Grafana) | 92.18.0 | `monitoring` | Helm, values in [`deploy/observability/k8s/values`](../observability/k8s/values/) |
| Tempo, Loki, Alloy (agents and tail sampler) | 2.10.10, 3.7.9, chart 1.15.0 | `monitoring` | Helm, same values directory ([observability README](../observability/README.md)) |

`verify.sh --server` checks every manifest against exactly these CRD versions.

Cluster-level objects the staging and prod overlays expect:

* `ClusterIssuer` `letsencrypt-staging` (staging) and `letsencrypt` (prod), with the DNS-01 solver of the zone's DNS provider.
* `ClusterSecretStore` `e-commerce`, pointing at that environment's vault, holding these keys:

| Key | Properties | Becomes |
|---|---|---|
| `e-commerce/orders-db`, `inventory-db`, `payments-db`, `catalog-db`, `keycloak-db` | `username`, `password` | database owner secrets in both namespaces |
| `e-commerce/notification-rabbitmq` | `username`, `password` | the RabbitMQ user and notification's `RABBITMQ_URL` |
| `e-commerce/object-storage` | `access_key`, `secret_key` | catalog's `S3_ACCESS_KEY`, `S3_SECRET_KEY` |
| `e-commerce/analytics` | `default_password`, `grafana_password` | ClickHouse passwords |

* An `egress-gateway` namespace (payment provider, object storage) and an ingress controller in `ingress-nginx` in front of
  Keycloak.
* Nodes with `vm.max_map_count=262144` for OpenSearch (the operator's privileged init container is off).

## Using dev

```bash
kubectl -n e-commerce-shop port-forward svc/edge 8443:443           # the shop API through the edge
kubectl -n e-commerce-platform port-forward svc/mailpit 8025        # emails
kubectl -n e-commerce-platform port-forward svc/minio 9000          # browser uploads (presigned URLs point here)
```

Tokens come from `https://id.dev.shop.example/realms/shop` with the test users (`ada` / `ada`, `sam` / `sam`), as in the
local stack's [smoke test](../compose/smoke.sh).

## Known gaps

| Gap | Why it is acceptable now | Next step |
|---|---|---|
| OpenSearch, Cassandra and Redis run without authentication | Only reachable inside the platform namespace's network policy; the services do not send credentials yet | Add credentials to search, review and the Redis clients, then enable security |
| Edge NGINX and ClickHouse run as root (warned against `restricted`, allowed by `baseline`) | Both drop to an unprivileged worker or user inside the image | Unprivileged NGINX image; ClickHouse with `runAsUser: 101` |
| Traces and logs are not collected | Metrics are scraped by Prometheus annotations | P5: OTel collector, Tempo, Loki |
