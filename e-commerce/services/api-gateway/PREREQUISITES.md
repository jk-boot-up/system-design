# Prerequisites: api-gateway (+ NGINX edge)

| Tool | Version | Why | Check |
|---|---|---|---|
| JDK | 25 | Build and run | `java -version` |
| Docker | 26+ | Container tests (Redis, the NGINX edge image), image builds | `docker version` |

The Gradle wrapper downloads Gradle and every dependency on first use. The edge image creates a self-signed certificate on start
when none is mounted, so local HTTPS works without any setup (clients must accept the certificate).

## Resources

| Task | Memory | Disk |
|---|---|---|
| Build and unit tests | 2 GB | 1 GB |
| Container tests | 3 GB | 1 GB |

## Verify

```bash
./gradlew test
./gradlew test -PwithContainers
docker build -t e-commerce/edge edge && docker run --rm -e GATEWAY_UPSTREAM=127.0.0.1:8080 e-commerce/edge sh -c '/docker-entrypoint.sh true >/dev/null && nginx -t'
```
