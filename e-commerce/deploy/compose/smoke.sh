#!/usr/bin/env bash
# Smoke test of the running core stack, through the NGINX edge like a browser:
# staff creates a product and stock, Ada fills a cart and checks out, the order is confirmed and stock goes down.
#   ./smoke.sh                 (needs curl and jq; the stack from: docker compose up -d --build)
set -euo pipefail
EDGE=${EDGE:-https://localhost:8443}
KC=${KC:-http://localhost:8180/realms/shop/protocol/openid-connect/token}
SKU="SKU-SMOKE-$(date +%s)"
c() { curl -sk --fail-with-body "$@"; }
token() { c -d grant_type=password -d client_id=shop-web -d "username=$1" -d "password=$1" "$KC" | jq -r .access_token; }
step() { printf '\n▶ %s\n' "$*"; }

step "tokens for sam (staff) and ada (customer)"
STAFF=$(token sam); ADA=$(token ada)

step "staff creates a category and the product $SKU"
CAT=$(c -X POST "$EDGE/api/v1/admin/categories" -H "authorization: Bearer $STAFF" -H 'content-type: application/json' \
  -d "{\"slug\":\"smoke-$(date +%s)\",\"name\":\"Smoke\"}" | jq -r .id)
PRODUCT=$(c -X POST "$EDGE/api/v1/admin/products" -H "authorization: Bearer $STAFF" -H 'content-type: application/json' \
  -d "{\"sku\":\"$SKU\",\"name\":\"Trail Runner 2\",\"categoryId\":\"$CAT\",\"brand\":\"Acme\",\"price\":{\"amount\":\"89.90\",\"currency\":\"EUR\"},\"status\":\"ACTIVE\"}")
echo "$PRODUCT" | jq -c '{id, sku, price}'
PRODUCT_ID=$(echo "$PRODUCT" | jq -r .id)

step "staff sets stock to 5 (gateway rewrites to inventory's internal API)"
c -X PUT "$EDGE/api/v1/admin/stock/$SKU" -H "authorization: Bearer $STAFF" -H 'content-type: application/json' -d '{"available":5}' | jq -c .

step "anyone can read the product; the edge caches it (MISS, then HIT)"
for i in 1 2 3; do curl -sk -o /dev/null "$EDGE/api/v1/products/$PRODUCT_ID"; [ $i = 1 ] && sleep 1; done
CACHE=$(curl -sk -o /dev/null -D - "$EDGE/api/v1/products/$PRODUCT_ID" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-cache"{print $2}')
echo "x-cache: $CACHE"

step "ada puts 2 in her cart"
c -X PUT "$EDGE/api/v1/cart/items/$SKU" -H "authorization: Bearer $ADA" -H 'content-type: application/json' -d '{"quantity":2}' | jq -c .

step "ada checks out"
ORDER=$(c -X POST "$EDGE/api/v1/orders" -H "authorization: Bearer $ADA" -H 'content-type: application/json' \
  -H "Idempotency-Key: smoke-$SKU" -d '{"paymentToken":"tok_ok"}')
echo "$ORDER" | jq -c '{id, status, total}'
STATUS=$(echo "$ORDER" | jq -r .status)

step "stock after the order"
STOCK=$(curl -s "http://localhost:8084/internal/stock/$SKU")
echo "$STOCK" | jq -c .

[ "$STATUS" = CONFIRMED ] && [ "$CACHE" = HIT ] && [ "$(echo "$STOCK" | jq .available)" = 3 ] && printf '\n✅ smoke test passed\n' || { printf '\n❌ smoke test failed\n'; exit 1; }
