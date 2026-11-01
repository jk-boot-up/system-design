#!/usr/bin/env python3
"""Fail if any relative link or image in this system's Markdown or HTML points to a missing file.

Usage: python3 tools/check_links.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKIP = {"node_modules", ".git", "build", ".gradle", ".venv"}
PATTERNS = [re.compile(r'\]\(([^)\s]+)\)'), re.compile(r'(?:href|src|srcset)="([^"]+)"')]


def targets(text):
    for pat in PATTERNS:
        for m in pat.finditer(text):
            link = m.group(1).split("#")[0].split("?")[0]
            if link and not re.match(r"^(https?:|mailto:|data:|javascript:|/)", link) and "${" not in link and "__" not in link:
                yield link


def main() -> int:
    broken = []
    for f in sorted(ROOT.rglob("*")):
        if f.suffix not in {".md", ".html"} or any(p in SKIP for p in f.relative_to(ROOT).parts) or f.parent.name == "tools":
            continue
        for link in targets(f.read_text(encoding="utf-8")):
            if not (f.parent / link).exists():
                broken.append(f"{f.relative_to(ROOT)} -> {link}")
    for b in broken:
        print("BROKEN", b)
    print(f"links checked, {len(broken)} broken")
    return 1 if broken else 0


if __name__ == "__main__":
    sys.exit(main())
