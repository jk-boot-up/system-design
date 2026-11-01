# Prerequisites: cart-service

| Tool | Version | Why | Check |
|---|---|---|---|
| JDK | 25 | Build and run | `java -version` |
| Docker | 26+ | Container tests (Redis, Kafka), local Redis and Kafka, image build | `docker version` |

The Gradle wrapper downloads Gradle and every dependency on first use. To see prices, catalog-service must be running (or the
cart shows `"pricing":"PENDING"`). Without Keycloak you can still use guest carts with an `X-Guest-Id` header.

## Resources

| Task | Memory | Disk |
|---|---|---|
| Build and unit tests | 2 GB | 1 GB |
| Container tests | 3 GB | 1 GB |

## Verify

```bash
./gradlew test
./gradlew test -PwithContainers
```
