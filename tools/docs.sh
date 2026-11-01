#!/usr/bin/env bash
# Rebuild the documentation: extract diagram sources, render changed diagrams to SVG, regenerate HTML pages.
set -euo pipefail
cd "$(dirname "$0")/.."
python3 tools/extract_diagrams.py
( cd tools/diagrams && { [ -d node_modules ] || npm install --no-audit --no-fund; } && node render.mjs )
python3 tools/md2html.py
