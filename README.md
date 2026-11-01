# System Design

Production-grade system designs. Each system has a specification, full design documents, animated walkthroughs and a working
reference implementation.

| System | What it is |
|---|---|
| [E-commerce platform](e-commerce/README.md) | Online store: catalogue, search, cart, checkout saga, inventory, payments, reviews, recommendations, notifications, analytics |

More systems (for example video streaming or ride hailing) will be added as sibling folders.

## How systems stay independent

Every system is a self-contained folder. Adding, changing or deleting one never affects another.

| Concern | Rule |
|---|---|
| Files | Everything for a system lives in its folder: documents, services, deployment, tests and documentation tooling. Nothing refers to another system's folder. |
| Builds | Each service inside a system builds on its own (its own Gradle wrapper, Go module or Python project). There is no root build. |
| Documentation | Each system renders its own diagrams and HTML pages with its own `tools/`, scoped to its own folder. |
| CI | Workflows are named `<system>-<project>.yml` and triggered only by `paths: <system>/<project>/**`. |
| Local runtime | Each system's Docker Compose file sets `name: <system>`, so containers, networks and volumes never collide. Each system documents its host-port range in its README. |
| Kubernetes | Namespaces are prefixed with the system name (`e-commerce-shop`, `e-commerce-platform`, …). |
| Code | Package and module names include the system (`com.jkbootup.ecommerce.*`), so artefacts from different systems never clash. |

The only shared files at this level are this index and the generic `.gitignore`.
