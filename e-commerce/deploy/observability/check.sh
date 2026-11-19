#!/usr/bin/env bash
# Checks the observability configuration without a running stack: generated files are current, Prometheus rules are valid
# and their unit tests pass, Alloy configurations parse, and the Kubernetes objects build. Needs Python 3, Docker and kubectl.
set -euo pipefail
cd "$(dirname "$0")"
PROMETHEUS=prom/prometheus:v3.15.1
ALLOY=grafana/alloy:v1.21.3

echo "━━ generated files"
python3 generate.py --check

echo "━━ promtool check rules, test rules"
docker run --rm -v "$PWD/prometheus:/p:ro" --entrypoint promtool "$PROMETHEUS" check rules /p/rules/slo.rules.yml /p/rules/platform.rules.yml
docker run --rm -v "$PWD/prometheus:/p:ro" -w /p/tests --entrypoint promtool "$PROMETHEUS" test rules alerts.test.yml

echo "━━ alloy fmt"
docker run --rm -v "$PWD/alloy:/a:ro" "$ALLOY" fmt /a/compose.alloy >/dev/null && echo "compose.alloy ok"
for f in alloy alloy-sampler; do
  python3 -c 'import sys,re; t=open(sys.argv[1]).read(); print(t.split("content: |\n",1)[1])' "k8s/values/$f.yaml" \
    | sed 's/^      //' | docker run --rm -i "$ALLOY" fmt - >/dev/null && echo "k8s/values/$f.yaml ok"
done

echo "━━ kustomize"
kubectl kustomize . | grep -c '^kind:' | xargs -I{} echo "{} objects"
