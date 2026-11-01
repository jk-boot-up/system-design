#!/usr/bin/env python3
"""Build every design/walkthrough/walkthrough.json into a self-contained index.html beside it.

The engine (engine.js, engine.css) and the scenario data are inlined, so each walkthrough is one file
that works offline and depends on nothing else in the repository.

Usage: python3 tools/walkthrough/build.py [path ...]   (default: the whole system folder)
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SKIP = {"node_modules", ".git", "build", ".gradle", ".venv"}
KINDS = {"client", "edge", "service", "db", "cache", "queue", "search", "external", "job", "analytics", "storage"}
PACKETS = {"req", "res", "event", "error"}


def validate(data: dict, where: Path) -> list[str]:
    errors = []
    ids = {n["id"] for n in data["nodes"]}
    for n in data["nodes"]:
        if n.get("kind") not in KINDS:
            errors.append(f"node {n['id']}: unknown kind {n.get('kind')}")
    for e in data.get("edges", []):
        for end in ("from", "to"):
            if e[end] not in ids:
                errors.append(f"edge {e}: unknown node {e[end]}")
    for s in data["scenarios"]:
        if len(s["steps"]) < 3:
            errors.append(f"scenario {s['id']}: fewer than 3 steps")
        for i, st in enumerate(s["steps"], 1):
            if not st.get("say"):
                errors.append(f"scenario {s['id']} step {i}: no narration")
            for key in ("active", "ok", "bad", "dim"):
                for n in st.get(key, []):
                    if n not in ids:
                        errors.append(f"scenario {s['id']} step {i}: {key} has unknown node {n}")
            for p in st.get("packets", []):
                if p["from"] not in ids or p["to"] not in ids:
                    errors.append(f"scenario {s['id']} step {i}: packet {p} has an unknown node")
                if p.get("kind", "req") not in PACKETS:
                    errors.append(f"scenario {s['id']} step {i}: packet kind {p.get('kind')}")
            for b in st.get("badges", []):
                if b["node"] not in ids:
                    errors.append(f"scenario {s['id']} step {i}: badge on unknown node {b['node']}")
    return [f"{where}: {e}" for e in errors]


def main() -> int:
    targets = [Path(a).resolve() for a in sys.argv[1:]] or [ROOT]
    sources = sorted({p for t in targets for p in (t.rglob("walkthrough.json") if t.is_dir() else [])
                      if not any(part in SKIP for part in p.parts) and p.parent.name == "walkthrough"})
    template = (HERE / "template.html").read_text(encoding="utf-8")
    css = (HERE / "engine.css").read_text(encoding="utf-8")
    js = (HERE / "engine.js").read_text(encoding="utf-8")
    problems = []
    for src in sources:
        data = json.loads(src.read_text(encoding="utf-8"))
        errs = validate(data, src.relative_to(ROOT))
        if errs:
            problems += errs
            continue
        payload = json.dumps(data, ensure_ascii=False).replace("</", "<\\/")
        page = (template.replace("/*__CSS__*/", css).replace("/*__JS__*/", js)
                .replace("/*__DATA__*/", payload).replace("__BACK__", "../README.html"))
        (src.parent / "index.html").write_text(page, encoding="utf-8")
    for p in problems:
        print("INVALID", p)
    print(f"built {len(sources) - len({p.split(':')[0] for p in problems})} walkthrough(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
