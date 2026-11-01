# 0011. Diagrams as rendered SVG from editable sources

| | |
|---|---|
| Status | Accepted |
| Date | 2026-11-01 |

## Context

Design documents need many diagrams that must stay correct, readable in both light and dark themes, and render the same everywhere.

## Decision

Diagram sources are kept as text (`design/diagrams/src/*.mmd`) and rendered to SVG in a light and a dark variant by the system's documentation pipeline. Documents embed the images; every Markdown file also has an HTML counterpart with a theme switch.

## Alternatives considered

| Option | Why not |
|---|---|
| Diagrams embedded as code blocks | Rendered differently by each viewer; syntax errors only show up when someone reads the page. |
| Hand-drawn images | Hard to keep in step with the design. |

## Consequences

* Rendering errors fail the documentation build.
* Images look the same on GitHub, in HTML pages and offline.
