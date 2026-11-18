# Prerequisites: analytics

| Tool | Version | Why | Check |
|---|---|---|---|
| Docker | 26+ | The ClickHouse image and the tests | `docker version` |
| Python | 3.14 | Test harness (pytest, aiokafka, testcontainers) | `python3.14 --version` |

## Resources

| Task | Memory | Disk |
|---|---|---|
| Tests (ClickHouse + Kafka) | 3 GB | 2 GB |
| Production | 4 GB per replica to start | ~1 KB per order event compressed; 100 GB holds years |

## Verify

```bash
.venv/bin/pytest
```
