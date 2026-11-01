# Prerequisites: order-service

| Tool | Version | Why | Check |
|---|---|---|---|
| JDK | 25 | Build and run | `java -version` |
| Docker | 26+ | Container tests (`-PwithContainers`), local PostgreSQL, Kafka and Keycloak, image build | `docker version` |

The Gradle wrapper downloads Gradle 9.8.2 and all dependencies on first use.

## To run checkout locally

Checkout calls three other services. Either run them too (`cart-service`, `inventory-service`, `payment-service`, each with its own
README), or start the whole stack from `../../deploy/compose`. A customer access token comes from the Keycloak realm `shop`, which
the Compose stack imports with test users.

## Resources

| Task | Memory | Disk |
|---|---|---|
| Build and unit tests | 2 GB | 1 GB |
| Container tests (PostgreSQL + Kafka) | 4 GB | 2 GB |

## Verify

```bash
./gradlew test
./gradlew test -PwithContainers
```
