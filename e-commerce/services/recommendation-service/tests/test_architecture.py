"""Hexagonal boundaries: the domain and the use cases import no frameworks and no adapters."""

import ast
from pathlib import Path

SRC = Path(__file__).parent.parent / "src" / "recommendation"
FORBIDDEN = ("redis", "aiokafka", "fastapi", "uvicorn", "prometheus_client", "pydantic")


def imports(path: Path) -> set[str]:
    names = set()
    for node in ast.walk(ast.parse(path.read_text())):
        if isinstance(node, ast.Import):
            names |= {a.name for a in node.names}
        elif isinstance(node, ast.ImportFrom):
            names.add(("." * node.level) + (node.module or ""))
    return names


def test_domain_and_app_are_framework_free():
    for name in ("domain.py", "app.py"):
        found = imports(SRC / name)
        assert not [i for i in found if i.split(".")[0] in FORBIDDEN or "adapters" in i], (name, found)


def test_adapters_do_not_import_each_other():
    for path in (SRC / "adapters").glob("*.py"):
        others = {p.stem for p in (SRC / "adapters").glob("*.py")} - {path.stem, "__init__"}
        assert not [i for i in imports(path) if i.lstrip(".") in others], path.name
