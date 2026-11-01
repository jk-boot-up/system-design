#!/usr/bin/env python3
"""Render every Markdown file in the repository to an HTML file next to it.

* Diagram <picture> elements become images that follow the page theme (light or dark SVG).
* Links to .md files are rewritten to .html.
* Each page has the light / medium / dark theme switch used across the project.

Usage: python3 tools/md2html.py            (from the repository root)
Needs: pip install -r tools/requirements-docs.txt
"""
import html
import re
import sys
from pathlib import Path

import markdown

ROOT = Path(__file__).resolve().parent.parent
SKIP_DIRS = {".git", ".gradle", "build", "node_modules", ".venv", "venv", "__pycache__", "vendor", "bin"}
TEMPLATE = (Path(__file__).parent / "md2html_template.html").read_text(encoding="utf-8")


PICTURE = re.compile(
    r'<picture>\s*<source media="\(prefers-color-scheme: dark\)" srcset="([^"]+)">\s*<img alt="([^"]*)" src="([^"]+)">\s*</picture>', re.S)


def themed_images(body: str) -> str:
    return PICTURE.sub(
        lambda m: f'<figure class="diagram"><img alt="{m.group(2)}" src="{m.group(3)}" data-light="{m.group(3)}" '
                  f'data-dark="{m.group(1)}" loading="lazy"></figure>', body)


EXTENSIONS = ["tables", "toc", "attr_list", "sane_lists", "md_in_html", "pymdownx.superfences", "pymdownx.highlight", "pymdownx.tilde"]
CONFIG = {
    "toc": {"permalink": "#", "toc_depth": "2-3"},
    "pymdownx.highlight": {"use_pygments": False},
}

LINK = re.compile(r'href="(?!https?://|mailto:)([^"#]*?)\.md(#[^"]*)?"')


def render(md_path: Path) -> Path:
    text = md_path.read_text(encoding="utf-8")
    md = markdown.Markdown(extensions=EXTENSIONS, extension_configs=CONFIG)
    body = themed_images(md.convert(text))
    body = LINK.sub(lambda m: f'href="{m.group(1)}.html{m.group(2) or ""}"', body)
    # links to a folder: point at its README.html when one exists
    def folder(m):
        target = (md_path.parent / m.group(1)).resolve()
        if target.is_dir() and (target / "README.md").exists():
            return f'href="{m.group(1).rstrip("/")}/README.html"'
        return m.group(0)
    body = re.sub(r'href="(?!https?://|mailto:|#)([^"]+/)"', folder, body)
    title_match = re.search(r"^#\s+(.+)$", text, re.M)
    title = title_match.group(1).strip() if title_match else md_path.stem
    depth = len(md_path.relative_to(ROOT).parts) - 1
    home = "../" * depth + "README.html"
    out = md_path.with_suffix(".html")
    out.write_text(
        TEMPLATE.replace("__TITLE__", html.escape(title)).replace("__HOME__", home)
        .replace("__SOURCE__", md_path.name).replace("__BODY__", body),
        encoding="utf-8",
    )
    return out


def main() -> int:
    count = 0
    for path in sorted(ROOT.rglob("*.md")):
        if any(part in SKIP_DIRS for part in path.relative_to(ROOT).parts):
            continue
        render(path)
        count += 1
    print(f"rendered {count} markdown files to html")
    return 0


if __name__ == "__main__":
    sys.exit(main())
