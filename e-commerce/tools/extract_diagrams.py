#!/usr/bin/env python3
"""Move diagram sources out of Markdown and reference rendered SVG images instead.

Every ```mermaid block found in a Markdown file is written to <docs>/diagrams/src/<name>.mmd and
replaced by a <picture> element that shows <docs>/diagrams/<name>.light.svg, or the .dark.svg
variant when the reader uses a dark colour scheme. <docs> is the nearest enclosing folder named
"docs", or a "docs" folder next to the Markdown file, so every project keeps its own diagrams.
Run tools/diagrams (npm run render) afterwards to produce the images.

Usage: python3 tools/extract_diagrams.py [path ...]   (default: the whole system folder)
"""
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKIP_DIRS = {".git", ".gradle", "build", "node_modules", ".venv", "venv", "vendor"}
BLOCK = re.compile(r"^```mermaid\n(.*?)^```\n", re.S | re.M)
HEADING = re.compile(r"^#{1,6}\s+(.+)$", re.M)


def slug(text: str) -> str:
    text = re.sub(r"^[\d.]+\s*", "", text.lower())
    return re.sub(r"[^a-z0-9]+", "-", text).strip("-")[:48] or "diagram"


def diagram_dir(md_path: Path) -> Path:
    for parent in md_path.parents:
        if parent == ROOT.parent:
            break
        if parent.name == "docs":
            return parent / "diagrams"
    return md_path.parent / "docs" / "diagrams"


def picture(md_path: Path, name: str, alt: str) -> str:
    rel = os.path.relpath(diagram_dir(md_path), md_path.parent).replace(os.sep, "/")
    return (
        "<picture>\n"
        f'  <source media="(prefers-color-scheme: dark)" srcset="{rel}/{name}.dark.svg">\n'
        f'  <img alt="{alt}" src="{rel}/{name}.light.svg">\n'
        "</picture>\n"
    )


def process(md_path: Path) -> int:
    text = md_path.read_text(encoding="utf-8")
    if "```mermaid" not in text:
        return 0
    OUT = diagram_dir(md_path)
    SRC = OUT / "src"
    prefix = slug(md_path.stem) if md_path.stem.lower() != "readme" else slug(md_path.parent.name)
    out, pos, count = [], 0, 0
    for m in BLOCK.finditer(text):
        before = text[: m.start()]
        headings = HEADING.findall(before)
        title = headings[-1].strip() if headings else md_path.stem
        base = f"{prefix}--{slug(title)}"
        name, n = base, 2
        while (SRC / f"{name}.mmd").exists():
            name, n = f"{base}-{n}", n + 1
        SRC.mkdir(parents=True, exist_ok=True)
        (SRC / f"{name}.mmd").write_text(m.group(1), encoding="utf-8")
        alt = re.sub(r"[\"<>]", "", re.sub(r"^[\d.]+\s*", "", title)) + " diagram"
        out.append(text[pos: m.start()])
        out.append(picture(md_path, name, alt))
        pos = m.end()
        count += 1
    out.append(text[pos:])
    md_path.write_text("".join(out), encoding="utf-8")
    return count


def main() -> int:
    targets = [Path(a).resolve() for a in sys.argv[1:]] or [ROOT]
    total = 0
    files = sorted({p for t in targets for p in ([t] if t.is_file() else t.rglob("*.md"))})
    for path in files:
        if any(p in SKIP_DIRS for p in path.relative_to(ROOT).parts):
            continue
        total += process(path)
    print(f"extracted {total} diagram(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
