#!/usr/bin/env bash
# Runs every journey against the local compose stack, one optional profile at a time so it fits in 4 GB of Docker memory:
# the core stack and its journeys first, then each profile is started, its journeys run (a missing service fails, not
# skips), and the profile is stopped again.
#   ./run-compose.sh                       core search reviews async analytics
#   ./run-compose.sh core async            only these
#   KEEP=1 ./run-compose.sh search         leave the profile running afterwards
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
compose="$here/../../deploy/compose"
export COMPOSE_PARALLEL_LIMIT=1   # one image build at a time: parallel Gradle builds exhaust a 4 GB Docker VM

profiles=("$@"); [[ ${#profiles[@]} -gt 0 ]] || profiles=(core search reviews async analytics)
[[ -d "$here/node_modules" ]] || (cd "$here" && npm ci --no-audit --no-fund)

core_services=$(cd "$compose" && docker compose config --services | sort)
# Core services a profile's journeys do not use, stopped while it runs to make room (OpenSearch alone needs ~1.5 GB).
idle_for() { case "$1" in search) echo "order-service payment-service cart-service" ;; esac; }   # bash 3.2 (macOS): no maps
failed=()

for p in "${profiles[@]}"; do
  printf '\n━━ %s ━━\n' "$p"
  if [[ "$p" == core ]]; then
    (cd "$compose" && docker compose up -d --build --wait)
    (cd "$here" && npx playwright test --grep-invert "@search|@reviews|@async|@analytics") || failed+=("$p")
    continue
  fi
  extra=$(cd "$compose" && comm -13 <(echo "$core_services") <(docker compose --profile "$p" config --services | sort) | tr '\n' ' ')
  idle=$(idle_for "$p")
  # shellcheck disable=SC2086
  [[ -z "$idle" ]] || (cd "$compose" && docker compose stop $idle >/dev/null)
  # shellcheck disable=SC2086
  (cd "$compose" && docker compose --profile "$p" up -d --build --wait $extra)
  (cd "$here" && E2E_REQUIRE="$p" npx playwright test --grep "@$p") || failed+=("$p")
  # shellcheck disable=SC2086
  [[ -n "${KEEP:-}" ]] || (cd "$compose" && docker compose --profile "$p" stop $extra >/dev/null)
  # shellcheck disable=SC2086
  [[ -z "$idle" || -n "${KEEP:-}" ]] || (cd "$compose" && docker compose up -d --wait $idle >/dev/null)
done

if [[ ${#failed[@]} -gt 0 ]]; then printf '\n❌ failed: %s\n' "${failed[*]}"; exit 1; fi
printf '\n✅ all journeys passed: %s\n' "${profiles[*]}"
