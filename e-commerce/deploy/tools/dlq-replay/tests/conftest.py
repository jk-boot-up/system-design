import os
import sys
from pathlib import Path

os.environ.setdefault("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
sys.path.insert(0, str(Path(__file__).parent.parent))
