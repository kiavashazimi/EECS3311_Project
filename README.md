# Project-Aware Research Consultant

**EECS 3311 — Software Design · Fall 2026 · York University**

A project-aware AI research assistant. It keeps a structured memory of the researcher's
own project — research question, datasets, models, metrics, results — and uses that
memory to find new papers, analyse them, judge which ones actually matter, and check
whether their published results are genuinely comparable to the researcher's own.

It is not a paper search tool. The distinguishing idea is that the system knows what
*you* are working on, and refuses to claim a comparison is valid when it is not.

---

## Stage 1 — Design

**The complete Stage 1 report is a single document:**

### 📄 [`docs/STAGE1.md`](docs/STAGE1.md)

Its nine sections correspond one-to-one to the nine required Stage 1 deliverables:

| # | Deliverable | Where |
|---|---|---|
| 1 | Project overview — problem, users, agent, LLM, architecture | [§1](docs/STAGE1.md#1-project-overview) |
| 2 | Feature specifications (13 features, F01–F13) | [§2](docs/STAGE1.md#2-feature-specifications) |
| 3 | UML class diagram | [§3](docs/STAGE1.md#3-uml-class-diagram) |
| 4 | Design pattern explanations (7 patterns) | [§4](docs/STAGE1.md#4-design-pattern-explanations) |
| 5 | Use-case diagram | [§5](docs/STAGE1.md#5-use-case-diagram) |
| 6 | Use-case descriptions (12 use cases, UC01–UC12) | [§6](docs/STAGE1.md#6-use-case-descriptions) |
| 7 | Sequence diagrams (9 diagrams, SD01–SD09) | [§7](docs/STAGE1.md#7-sequence-diagrams) |
| 8 | Feature-to-design traceability table | [§8](docs/STAGE1.md#8-feature-to-design-traceability) |
| 9 | How each feature is realized | [§9](docs/STAGE1.md#9-how-each-feature-is-realized) |

### Design patterns

Seven patterns, each explained in §4 with its own focused class diagram:

| Pattern | Applied to | Problem it solves |
|---|---|---|
| Facade | `AppController` | One entry point per feature, so the GUI and CLI never reach into the subsystem |
| Template Method | `Agent` | Every agent follows the same plan → use tools → summarise sequence |
| Strategy | `LiteratureSource` | arXiv and Semantic Scholar are interchangeable at runtime |
| Adapter | `LLMClient`, `ProjectContextProvider` | One interface over different LLM providers and sources of project context |
| Observer | `PaperFeedListener` | A newly discovered paper reaches the feed and the digest independently |
| Singleton | `ProjectMemory` | The GUI, the CLI and every agent share one project state |
| Composite | `StrategyNode` | The solution landscape is a tree of unknown depth, walked recursively |

### Diagrams

All diagrams appear inline in the report. Sources and rendered images are in
[`docs/diagrams/`](docs/diagrams/):

| File | Contents |
|---|---|
| `class-diagram.mmd` / `.png` | The full class diagram (§3) |
| `use-case-diagram.puml` / `use-case-render.png` | The use-case diagram (§5) |
| `sd01…sd09-*.mmd` / `.png` | The nine sequence diagrams (§7) |
| `patterns/pattern-*.mmd` / `.png` | One focused diagram per design pattern (§4) |

Diagrams are written in Mermaid and PlantUML and render directly in the report on GitHub.
Every diagram also links a rendered image beneath it.

---

## Planned implementation

| | |
|---|---|
| Language | Java |
| GUI | JavaFX desktop application |
| CLI | Full command-line access to every major feature |
| Build | Maven |
| AI | Claude, via the Anthropic Messages API, behind the `LLMClient` interface |

Implementation begins in Stage 2. This repository currently contains the Stage 1 design
only.

## Repository layout

```
docs/
  STAGE1.md            the Stage 1 design report
  diagrams/            diagram sources (.mmd, .puml) and rendered images (.png)
    patterns/          one class diagram per design pattern
README.md
```
