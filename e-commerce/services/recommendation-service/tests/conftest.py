import os

# Docker Desktop exposes the engine's socket inside its VM at /var/run/docker.sock; the clean-up container (Ryuk) mounts
# that path, not the host-side one the client connects through.
os.environ.setdefault("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
