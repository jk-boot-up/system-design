# 3. Deployment design

## 3.1 Production topology (one region, three availability zones)

```mermaid
flowchart TB
    users([Users]) --> cdn[CDN + WAF]
    cdn --> lb[Cloud load balancer]

    subgraph region[Region]
        subgraph k8s[Kubernetes cluster]
            ing[Ingress controller<br/>2+ replicas]
            subgraph ns_edge[namespace: edge]
                gw[api-gateway ×3..20]
            end
            subgraph ns_shop[namespace: shop]
                cat[catalog ×2..10]
                srch[search ×2..10]
                cart[cart ×2..10]
                ord[order ×3..20]
                inv[inventory ×3..20]
                pay[payment ×2..10]
                rev[review ×2..6]
                notif[notification router + email worker ×2..6]
                reco[recommendation ×2..6]
            end
            subgraph ns_platform[namespace: platform]
                kc[Keycloak ×2]
                otel[OTel collector<br/>DaemonSet]
                prom[Prometheus]
                graf[Grafana]
                tempo[Tempo]
                loki[Loki]
            end
        end

        subgraph az[Data tier spread across AZ-a, AZ-b, AZ-c]
            pg[(PostgreSQL clusters<br/>Patroni: primary + sync standby + async replica<br/>catalog, inventory, orders, payments, keycloak)]
            pgb[PgBouncer ×2 per cluster]
            kafka[(Kafka ×3 brokers, KRaft<br/>RF 3, min ISR 2)]
            redis[(Redis Cluster<br/>3 primaries + 3 replicas)]
            os[(OpenSearch ×3 data<br/>+ 3 cluster-manager)]
            cass[(Cassandra ×3, RF 3<br/>LOCAL_QUORUM)]
            minio[(MinIO ×4, erasure coded)]
            rmq[(RabbitMQ ×3, quorum queues)]
            ch[(ClickHouse ×2 replicas<br/>Keeper ×3)]
        end
    end

    lb --> ing --> gw
    gw --> cat & srch & cart & ord & rev
    ord --> inv & pay & cart
    cart --> cat
    cat & inv & ord & pay --> pgb --> pg
    cat & inv & ord & pay & rev & srch & cart & notif --> kafka
    cart & gw & notif --> redis
    srch --> os
    rev --> cass
    cat --> minio
    pay --> psp[Payment provider]
    notif --> smtp[Email relay]
    notif --> rmq
    reco --> redis
    kafka --> ch
    graf --> ch
```

Stateful systems run either as managed services or through Kubernetes operators:
* PostgreSQL: CloudNativePG or Patroni.
* Kafka: Strimzi.
* Redis: Redis operator.
* OpenSearch: OpenSearch operator.
* Cassandra: K8ssandra.
* MinIO: MinIO operator.
* RabbitMQ: RabbitMQ Cluster Operator.
* ClickHouse: Altinity ClickHouse operator.

## 3.2 Node pools and placement

```mermaid
flowchart LR
    subgraph AZa[AZ a]
        a1[general pool<br/>services]
        a2[memory pool<br/>Redis, OpenSearch]
        a3[storage pool<br/>PostgreSQL, Kafka, Cassandra]
    end
    subgraph AZb[AZ b]
        b1[general pool]
        b2[memory pool]
        b3[storage pool]
    end
    subgraph AZc[AZ c]
        c1[general pool]
        c2[memory pool]
        c3[storage pool]
    end
```

* **Spread:** `topologySpreadConstraints` with `maxSkew: 1` across zones and hosts for every Deployment and StatefulSet.
* **Isolation:** stateful pods use dedicated node pools (taints and tolerations) with local NVMe or provisioned-IOPS volumes.
* **Disruption budgets:** `PodDisruptionBudget` `minAvailable: 50%` for services; `maxUnavailable: 1` for data clusters.

## 3.3 Kubernetes objects per service

```mermaid
classDiagram
    class Deployment {
      replicas
      rollingUpdate maxSurge=25% maxUnavailable=0
      readinessProbe /actuator/health/readiness
      livenessProbe /actuator/health/liveness
      resources requests/limits
      securityContext runAsNonRoot readOnlyRootFilesystem
    }
    class Service { ClusterIP port 80 → 8080 }
    class HorizontalPodAutoscaler { min, max; CPU 60%; http_requests_per_second }
    class PodDisruptionBudget { minAvailable 50% }
    class ConfigMap { application.yaml profile overrides }
    class Secret { DB / Kafka / provider credentials from the external secret store }
    class ServiceAccount { workload identity; Kafka ACL principal }
    class NetworkPolicy { allowed ingress / egress only }
    Deployment --> ConfigMap
    Deployment --> Secret
    Deployment --> ServiceAccount
    Service --> Deployment
    HorizontalPodAutoscaler --> Deployment
    PodDisruptionBudget --> Deployment
    NetworkPolicy --> Deployment
```

**Network policies** (default deny, then allow):

| From | To |
|---|---|
| ingress controller | api-gateway |
| api-gateway | catalog, search, cart, order, review, recommendation |
| cart | catalog |
| order | cart, inventory, payment |
| all services | their own data stores, Kafka, OTel collector |
| payment | payment provider egress (FQDN allow-list) |
| notification | RabbitMQ, Redis, SMTP egress |
| recommendation | Kafka, Redis |

## 3.4 CI/CD pipeline

```mermaid
flowchart LR
    dev[Push / pull request] --> b[Build<br/>Gradle, Go, Python]
    b --> ut[Unit + ArchUnit tests]
    ut --> it[Integration tests<br/>Testcontainers]
    it --> sec[Dependency + image scan<br/>OWASP DC, Trivy]
    sec --> img[Build images<br/>layered jars, AppCDS, distroless Go]
    img --> sign[Sign + SBOM<br/>cosign, CycloneDX]
    sign --> devenv[Deploy dev<br/>Kustomize overlay]
    devenv --> perf[Gatling budgets]
    perf --> stg[Deploy staging]
    stg --> canary[Production canary 5%<br/>SLO gates]
    canary --> prod[Production 100%]
    canary -- SLO breach --> rb[Automatic rollback]
```

* **Migrations:** database migrations run as a Kubernetes `Job` before the rollout. They are always backward compatible (expand → migrate → contract), so old and new pods work against the same schema.
* **Event contracts:** these are checked in CI. A change that is not backward compatible fails the build unless it introduces a new event version.

## 3.5 Environments

| Environment | Purpose | Infrastructure | Data |
|---|---|---|---|
| local | Development | Docker Compose (`deploy/compose`) | Seed data |
| dev | Integration of merged changes | Kubernetes, single-replica data stores | Synthetic |
| staging | Release candidate, load tests | Production-like, smaller | Anonymised copy |
| production | Customers | As in §3.1 | Live |

## 3.6 Local stack (Docker Compose)

```mermaid
flowchart TB
    subgraph compose[docker compose]
        pg[(postgres:17<br/>databases: catalog, inventory, orders, payments, keycloak)]
        kafka[(kafka:4 KRaft, single node)]
        redis[(redis:8)]
        os[(opensearch:3 single node)]
        cass[(cassandra:5)]
        rmq[(rabbitmq:4-management)]
        ch[(clickhouse)]
        minio[(minio)]
        kc[keycloak<br/>realm import: shop]
        mail[mailpit<br/>SMTP + web UI]
        otel[otel-collector] --> tempo[tempo] & prom[prometheus]
        graf[grafana]
    end
    apps[Services: Java, Go, Python images] --> pg & kafka & redis & os & cass & minio & kc & mail & otel & rmq
    kafka --> ch
    graf --> ch
```

| Component | Local port |
|---|---|
| api-gateway | 8080 |
| services | 8081 – 8089 |
| RabbitMQ management | 15672 |
| ClickHouse HTTP | 8123 |
| Keycloak | 8180 |
| Mailpit UI | 8025 |
| Grafana | 3000 |
| MinIO console | 9001 |

## 3.7 Disaster recovery

| Store | Backup | RPO | RTO |
|---|---|---|---|
| PostgreSQL | Continuous WAL archiving + daily base backup to object storage; point-in-time recovery | ≈ 0 (sync standby) / 5 min (region) | 15 min |
| Kafka | Replication factor 3 across zones; topics can be rebuilt from outbox tables for 7 days | 0 within region | Minutes |
| Cassandra | Nightly snapshots + incremental backups (Medusa) | 24 h worst case; RF 3 normally | 1 h |
| OpenSearch | Snapshots; can always be rebuilt from catalog | n/a (derived) | Reindex time |
| Redis | AOF every second; carts are cheap to lose | ≤ 1 s | Minutes |
| MinIO | Erasure coding + bucket replication | 0 within cluster | Minutes |
| RabbitMQ | Quorum queues (Raft) across 3 nodes; jobs are rebuildable from Kafka | 0 within cluster | Minutes |
| ClickHouse | Replicated tables + backups; can be rebuilt by replaying Kafka | n/a (derived) | Replay time |
