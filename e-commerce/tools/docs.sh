#!/usr/bin/env bash
# Rebuild this system's documentation for the given paths (default: the whole system folder):
# extract diagram sources, render changed diagrams to SVG, regenerate HTML pages.
#   tools/docs.sh                     everything in e-commerce/
#   tools/docs.sh docs/design         only the system design documents
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
paths=()
for p in "$@"; do paths+=("$(cd "$(dirname "$p")" && pwd)/$(basename "$p")"); done
cd "$repo"
python3 tools/extract_diagrams.py "${paths[@]}"
( cd tools/diagrams && { [ -d node_modules ] || npm install --no-audit --no-fund; } && node render.mjs "${paths[@]}" )
python3 tools/md2html.py "${paths[@]}"
