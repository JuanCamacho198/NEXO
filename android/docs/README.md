# Project documentation

Architecture, UX, diagrams, and onboarding docs for the NEXO Android app live here. This folder is the single, long-lived home for prose that does not belong in `AGENTS.md` (a quick-reference card) or in code (KDoc).

## Purpose

The docs follow a **four-tier layout** that mirrors how the codebase is organized, so contributors can predict where new prose belongs:

| Tier | Folder | What goes here |
|------|--------|----------------|
| Why | `architecture/` | Component shapes, data flows, key decisions — the "why" tier. |
| What | `ui/` | Screen-by-screen UX docs that mirror `presentation/screen/`. |
| How-to-start | `onboarding/` | First build, first deploy, first contribution. |
| Cross-cutting | `diagrams/` | Shared Mermaid sources reused by two or more docs. |

Each tier answers a different question. Pick the tier that matches the question your reader is asking, then write to its template.

## Map

| Folder | Holds | Author template |
|--------|-------|-----------------|
| `docs/architecture/` | Component shapes, data flows, design decisions. Auth worked example: `data-flow.md`. | `data-flow.md` (Cognitive Doc Design) |
| `docs/ui/` | Per-screen UX notes mirroring `presentation/screen/`. | `ui/README.md` stub |
| `docs/diagrams/` | Shared Mermaid sources. Use only when a diagram is referenced by ≥ 2 docs. | `diagrams/README.md` stub |
| `docs/onboarding/` | First build, first deploy, first contribution. | `onboarding/README.md` stub |
| `docs/design-traceability.md` | Canonical Pencil node ↔ code index. | n/a (single source of truth) |

## Template

The reference worked example for any new doc in this folder is **`docs/architecture/data-flow.md`**. It locks in the Cognitive Doc Design pattern we use everywhere:

1. **Outcome-oriented title** + one-paragraph summary naming the actors.
2. **`## Quick path`** — three numbered steps the reader can execute.
3. **`## Details`** — table-driven facts and inline Mermaid diagrams.
4. **`## Checklist`** — verification items tied to spec scenarios.
5. **`## Next step`** — pointer to a related doc.
6. **`## Design Traceability`** — code-path ↔ Pencil-node mapping (project requirement).

Copy that skeleton, then specialize the Details section for your topic. Do not start a doc with prose; start with the answer.

## Checklist for new docs

- [ ] Outcome-oriented title and one-paragraph summary.
- [ ] `## Quick path` with ≥ 3 numbered steps.
- [ ] `## Details` is table-driven (no prose walls).
- [ ] `## Checklist` covers the verification scenario.
- [ ] `## Design Traceability` links every documented code path to a Pencil node ID.
- [ ] Pointer to the next doc the reader should open.

## Next step

Open `docs/architecture/data-flow.md` to see the worked example in full.
