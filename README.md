# System Design

Production-grade system designs, each with a specification, full design documents, animated walkthroughs and a working reference
implementation.

| System | What it is |
|---|---|
| [E-commerce platform](e-commerce/README.md) | Online store: catalogue, search, cart, checkout saga, inventory, payments, reviews, recommendations, notifications, analytics |

## Repository conventions

* **One folder per system.** Everything that belongs to a system — its documents, services, deployment and tests — lives inside it.
* **Independent projects.** Inside a system every service is a standalone project with its own build, dependencies, documents,
  container image and pipeline. Services share no code; they agree only on contracts (APIs and event schemas).
* **Documents in two forms.** Every Markdown file has an HTML counterpart beside it with light, medium and dark themes. Diagrams are
  SVG images (light and dark variants) rendered from editable sources in each `docs/diagrams/src` folder.
* **Tooling.** `tools/` holds the documentation pipeline only; no system depends on it to build or run.

```bash
tools/docs.sh      # extract new diagram sources, render changed diagrams to SVG, regenerate all HTML pages
```
