#!/usr/bin/env bash
# Runs the simulations against the local compose stack with the performance overlay (compose.performance.yaml), one at a
# time, then checks p99 against the baseline (scripts/regression.py). Search needs the "search" profile; order, payment
# and cart are stopped while it runs so OpenSearch fits in a 4 GB Docker VM, and started again afterwards.
#   ./run-compose.sh                          Browse Cart Checkout Search
#   ./run-compose.sh Browse Search            only these
#   UPDATE_BASELINE=1 ./run-compose.sh        accept this run as the new baseline
#   PERF_LOAD=target PERF_EDGE=https://… ./run-compose.sh   elsewhere (see README)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
compose="$here/../../deploy/compose"
export COMPOSE_PARALLEL_LIMIT=1   # one image build at a time: parallel Gradle builds exhaust a 4 GB Docker VM
files=(-f compose.yaml -f "$here/compose.performance.yaml")

simulations=("$@"); [[ ${#simulations[@]} -gt 0 ]] || simulations=(Browse Cart Checkout Search)
search_idle="order-service payment-service cart-service"
search_services="opensearch search-service"
failed=()

# No --build: images are built by the e2e runner or the first "up"; a load test should not wait on a rebuild.
(cd "$compose" && docker compose "${files[@]}" up -d --wait)
for s in "${simulations[@]}"; do
  printf '\n━━ %s ━━\n' "$s"
  if [[ "$s" == Search ]]; then
    # Name the services: "up" without them would start the stopped ones again.
    # shellcheck disable=SC2086
    if ! (cd "$compose" && docker compose stop $search_idle >/dev/null && docker compose "${files[@]}" --profile search up -d --wait $search_services); then
      failed+=("$s (stack)")
    else
      (cd "$here" && ./gradlew -q gatlingRun --simulation "com.jkbootup.ecommerce.performance.${s}Simulation") || failed+=("$s")
    fi
    # shellcheck disable=SC2086
    (cd "$compose" && docker compose --profile search stop $search_services >/dev/null && docker compose "${files[@]}" up -d --wait $search_idle >/dev/null) || failed+=("restart after $s")
    continue
  fi
  (cd "$here" && ./gradlew -q gatlingRun --simulation "com.jkbootup.ecommerce.performance.${s}Simulation") || failed+=("$s")
done

printf '\n━━ p99 against the baseline ━━\n'
(cd "$here" && python3 scripts/regression.py ${UPDATE_BASELINE:+--update}) || failed+=(regression)

if [[ ${#failed[@]} -gt 0 ]]; then printf '\n❌ failed: %s\n' "${failed[*]}"; exit 1; fi
printf '\n✅ every budget met: %s\n' "${simulations[*]}"
