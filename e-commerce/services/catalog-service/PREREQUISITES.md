# Prerequisites: catalog-service

| Tool | Version | Why | Check |
|---|---|---|---|
| JDK | 25 | Build and run | `java -version` |
| Docker | 26+ | Container tests (PostgreSQL, Redis, Kafka, RustFS for S3), local infrastructure, image build | `docker version` |

The Gradle wrapper downloads Gradle and every dependency on first use. Public reads need no token. The staff API needs a token
with the `staff` role from Keycloak (`deploy/compose` imports the `shop` realm with a staff user).

## Resources

| Task | Memory | Disk |
|---|---|---|
| Build and unit tests | 2 GB | 1 GB |
| Container tests | 4 GB | 2 GB |

## Verify

```bash
./gradlew test
./gradlew test -PwithContainers
```
