# Prerequisites: review-service

| Tool | Version | Why | Check |
|---|---|---|---|
| JDK | 25 | Build and run | `java -version` |
| Docker | 26+ | Container tests (Cassandra, Kafka), local infrastructure, image build | `docker version` |

The Gradle wrapper downloads Gradle and every dependency on first use. Reading reviews needs no token; posting needs a customer
token from Keycloak (`deploy/compose` imports the `shop` realm).

## Resources

| Task | Memory | Disk |
|---|---|---|
| Build and unit tests | 2 GB | 1 GB |
| Container tests | 4 GB (Cassandra takes about 1.5 GB) | 2 GB |

## Verify

```bash
./gradlew test
./gradlew test -PwithContainers
```
