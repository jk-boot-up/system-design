# Prerequisites: search-service

| Tool | Version | Why | Check |
|---|---|---|---|
| JDK | 25 | Build and run | `java -version` |
| Docker | 26+ | Container tests (OpenSearch, Kafka), local infrastructure, image build | `docker version` |

The Gradle wrapper downloads Gradle and every dependency on first use. Search needs no token: it is a public, read-only API.

## Resources

| Task | Memory | Disk |
|---|---|---|
| Build and unit tests | 2 GB | 1 GB |
| Container tests | 4 GB (OpenSearch takes about 1 GB) | 2 GB |

## Verify

```bash
./gradlew test
./gradlew test -PwithContainers
```
