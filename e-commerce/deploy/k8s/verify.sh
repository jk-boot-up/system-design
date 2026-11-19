#!/usr/bin/env bash
# Checks the Kubernetes overlays.
#   ./verify.sh            build every overlay with kustomize (fast, no cluster)
#   ./verify.sh --server   also send every object to a real API server as a server-side dry run with strict field
#                          validation, against the CRDs of the operator versions below. Uses (and keeps) a kind cluster
#                          named e-commerce-verify with its own kubeconfig, so your kubectl context is left alone;
#                          delete it with: kind delete cluster --name e-commerce-verify
# Needs kubectl (with kustomize built in); --server also needs kind, curl and Docker.
set -euo pipefail
cd "$(dirname "$0")"

OVERLAYS=(dev staging prod)
CLUSTER=e-commerce-verify
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/e-commerce-k8s-crds"

# Operator versions the manifests are written against (keep in step with README.md).
CRDS=(
  "cert-manager      https://github.com/cert-manager/cert-manager/releases/download/v1.21.3/cert-manager.crds.yaml"
  "cloudnative-pg    https://github.com/cloudnative-pg/cloudnative-pg/releases/download/v1.30.2/cnpg-1.30.2.yaml"
  "strimzi           https://github.com/strimzi/strimzi-kafka-operator/releases/download/1.3.1/strimzi-crds-1.3.1.yaml"
  "redis-operator    https://raw.githubusercontent.com/OT-CONTAINER-KIT/redis-operator/v0.27.0/config/crd/bases/redis.redis.opstreelabs.in_redis.yaml"
  "opensearch        https://raw.githubusercontent.com/opensearch-project/opensearch-k8s-operator/v3.0.0/opensearch-operator/config/crd/bases/opensearch.org_opensearchclusters.yaml"
  "k8ssandra         https://raw.githubusercontent.com/k8ssandra/k8ssandra-operator/v1.34.0/config/crd/bases/k8ssandra.io_k8ssandraclusters.yaml"
  "rabbitmq          https://github.com/rabbitmq/cluster-operator/releases/download/v2.23.3/cluster-operator.yml"
  "rabbitmq-topology https://github.com/rabbitmq/messaging-topology-operator/releases/download/v1.20.6/messaging-topology-operator.yaml"
  "keycloak          https://raw.githubusercontent.com/keycloak/keycloak-k8s-resources/26.8.2/kubernetes/keycloaks.k8s.keycloak.org-v1.yml"
  "keycloak-realms   https://raw.githubusercontent.com/keycloak/keycloak-k8s-resources/26.8.2/kubernetes/keycloakrealmimports.k8s.keycloak.org-v1.yml"
  "external-secrets  https://raw.githubusercontent.com/external-secrets/external-secrets/v2.15.0/deploy/crds/bundle.yaml"
  "keda              https://github.com/kedacore/keda/releases/download/v2.21.1/keda-2.21.1-crds.yaml"
)

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

for o in "${OVERLAYS[@]}"; do
  kubectl kustomize "overlays/$o" > "$tmp/$o.yaml"
  echo "build   $o: $(grep -c '^kind:' "$tmp/$o.yaml") objects"
done

[[ "${1:-}" == "--server" ]] || exit 0

mkdir -p "$CACHE"
export KUBECONFIG="$CACHE/kubeconfig"
if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  kind create cluster --name "$CLUSTER" --kubeconfig "$KUBECONFIG" --wait 120s
fi
kind export kubeconfig --name "$CLUSTER" --kubeconfig "$KUBECONFIG" >/dev/null 2>&1
ctx="kind-$CLUSTER"

for entry in "${CRDS[@]}"; do
  read -r name url <<<"$entry"
  file="$CACHE/$name.yaml"
  [[ -s "$file" ]] || curl -fsSL "$url" -o "$file"
  # Keep only the CustomResourceDefinitions (some files are whole operator installs).
  awk 'function flush() { if (doc ~ /(^|\n)kind: "?CustomResourceDefinition/) printf "---\n%s", doc; doc = "" }
       /^---/ { flush(); next } { doc = doc $0 "\n" } END { flush() }' "$file" > "$tmp/crd-$name.yaml"
  kubectl --context "$ctx" apply --server-side --force-conflicts -f "$tmp/crd-$name.yaml" >/dev/null
  echo "crds    $name"
done
kubectl --context "$ctx" wait --for condition=Established crd --all --timeout 120s >/dev/null
kubectl --context "$ctx" apply -f base/namespaces.yaml >/dev/null

for o in "${OVERLAYS[@]}"; do
  kubectl --context "$ctx" apply --server-side --dry-run=server --validate=strict --field-manager verify \
    -f "$tmp/$o.yaml" > "$tmp/$o.out"
  echo "server  $o: $(grep -c 'serverside-applied' "$tmp/$o.out") objects accepted"
done
