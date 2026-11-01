#!/usr/bin/env bash
# Rebuild this system's documentation for the given paths (default: the whole system folder):
# extract diagram sources, render changed diagrams to SVG, regenerate HTML pages.
#   tools/docs.sh                     everything in e-commerce/
#   tools/docs.sh design              only the whole-system design documents
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
paths=()
for p in "$@"; do paths+=("$(cd "$(dirname "$p")" && pwd)/$(basename "$p")"); done
cd "$repo"
python3 tools/extract_diagrams.py "${paths[@]}"
( cd tools/diagrams && { [ -d node_modules ] || npm install --no-audit --no-fund; } && node render.mjs "${paths[@]}" )
python3 tools/md2html.py "${paths[@]}"
python3 tools/walkthrough/build.py "${paths[@]}"
if command -v node >/dev/null && node -e "require('playwright')" 2>/dev/null; then
  find "${paths[@]:-.}" -path '*/design/walkthrough/index.html' -not -path '*/node_modules/*' -print0 | xargs -0 -r node tools/walkthrough/check.cjs
fi
