# Prerequisites: recommendation-service

| Tool | Version | Why | Check |
|---|---|---|---|
| Python | 3.14 | Run and test | `python3.14 --version` |
| Docker | 26+ | Container tests (Redis, Kafka), image build | `docker version` |

`pip install -e '.[test]'` in a virtual environment installs the exact versions pinned in `pyproject.toml`.

On Docker Desktop the tests set `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` (in `tests/conftest.py`) so the
clean-up container can mount the engine's socket.

## Resources

| Task | Memory | Disk |
|---|---|---|
| Unit tests | 512 MB | 300 MB |
| Container tests | 2 GB | 1.5 GB |

## Verify

```bash
.venv/bin/pytest
.venv/bin/pytest -m containers
```
