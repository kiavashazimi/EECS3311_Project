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
| `class-diagram.mmd` / `class-diagram-render.png` | The Stage 1 design class diagram (§3) |
| `class-diagram-stage2-*.mmd` / `.png` | The Stage 2 as-built class diagram, in four views |
| `use-case-diagram.puml` / `use-case-render.png` | The use-case diagram (§5) |
| `sd01…sd09-*.mmd` / `.png` | The nine sequence diagrams (§7) |
| `patterns/pattern-*.mmd` / `.png` | One focused diagram per design pattern (§4) |

Diagrams are written in Mermaid and PlantUML and render directly in the report on GitHub.
Every diagram also links a rendered image beneath it.

---

## Stage 2 — Implementation

**The complete Stage 2 report:** [`docs/STAGE2.md`](docs/STAGE2.md), submitted as
[`docs/stage2-report.pdf`](docs/stage2-report.pdf).

All thirteen Stage 1 features are implemented and work from **both** the GUI and the CLI.
None was dropped. All seven design patterns above are in running code. 573 tests pass; the
four skipped tests are the live network and live API tests, disabled by default so that
`mvn test` never spends money or depends on a third-party service.

| Report section | Contents |
|---|---|
| [§1](docs/STAGE2.md#1-project-overview-and-implementation-status) | Overview, working functionality, and the limitations stated honestly |
| [§2](docs/STAGE2.md#2-feature-to-implementation-mapping) | Every feature mapped to its classes, methods, patterns and demo |
| [§3](docs/STAGE2.md#3-implemented-design-patterns) | The seven patterns as built, with the updated class diagram |
| [§4](docs/STAGE2.md#4-changes-and-rationale) | 27 changes from the Stage 1 design, with reasons |
| [§5](docs/STAGE2.md#5-ai-tools-and-collaboration-log) | AI tools and collaboration log |
| [§6](docs/STAGE2.md#6-development-and-verification-summary) | Commit summary, verification, defects found and fixed |
| [§7](docs/STAGE2.md#7-demonstration-index) | Feature-by-feature demonstration index |

The as-built class diagram is four views covering all 55 classes — see
[`docs/diagrams/README.md`](docs/diagrams/README.md) for which is which, and §4 of the
Stage 2 report for why it is four rather than one.

---

## Running it

Needs **JDK 17** and Maven. The project targets Java 17, so point `JAVA_HOME` at a full
JDK 17 — not a runtime-only install, which has no compiler and fails with the misleading
`release version 17 not supported`.

```bash
export JAVA_HOME=/path/to/jdk-17
mvn test          # 405 tests, offline, no API key needed
```

### The desktop app

```bash
./rc-gui          # or: mvn javafx:run
```

### The command line

```bash
./rc help
```

Both clients go through the same `AppController`, so every feature behaves identically in
each.

```bash
# 1. describe your project once
./rc profile set \
  --question "Does adding synthetic chest X-rays make a classifier fairer across subgroups?" \
  --datasets "MIMIC-CXR, CheXpert" \
  --metrics  "AUROC, underdiagnosis gap" \
  --models   "conditional diffusion model, DenseNet-121"

./rc profile show

# 2. find papers that matter for it, ranked with a reason each
./rc discover

# 3. work on one paper, by its arXiv id or by its row number in that listing
./rc analyze 2412.20374v2     # extract its structure and tag its strategy
./rc compare 2                # can its numbers be compared with yours?
./rc repo    2                # does its code exist and look reusable?

# 4. your own project
./rc import ~/projects/my-study   # read its current state
./rc changes                      # what changed since last time

# 5. ask about what you have gathered
./rc ask   "which of my papers condition the generator on demographics?"
./rc prior "oversampling synthetic X-rays for the worst-performing subgroup"

# 6. the field as a whole
./rc landscape                    # solution approaches, and where yours sits

# 7. what is new since last time
./rc digest                       # new papers and project changes
```

### API key

The deterministic features — profile validation, comparability, change diffs, the landscape
structure — work with **no key at all**. Anything that writes prose needs one:

```bash
cp .env.example .env
# then set LLM_API_KEY in .env  (never in .env.example, which is tracked)
```

Without a key, those features still return their deterministic result and label the missing
narrative rather than inventing one. `.env` is gitignored, and `Config.toString()` prints
`llmApiKey=set` rather than the key.

### Literature sources

Paper search uses **arXiv** by default. Setting `S2_API_KEY` in `.env` switches the primary
source to **Semantic Scholar**, with arXiv as the automatic fallback when it cannot be
reached — its keyed rate limit is one request per second, so a rejection is an ordinary
event rather than an exceptional one, and the switch is written to the tool call log so
results are never mistaken for the other source's.

Semantic Scholar is worth the second source for its index rather than its query handling:
on one measured query, 4 of 10 results were not on arXiv at all (AMIA, PLOS Digital Health,
conference proceedings), and it reports the **published venue** — *Nature Medicine* for a
paper arXiv's own API describes only as `arXiv cs.CV`. Both sources need short, term-reduced
queries; neither handles a whole research question.

> This project uses the **Semantic Scholar** API, provided by the Allen Institute for AI.
> Work reporting results obtained through it should cite *The Semantic Scholar Open Data
> Platform*. Get a free key at
> <https://www.semanticscholar.org/product/api#api-key-form>.

### Exit codes

`0` success · `1` usage error · `2` validation failed, nothing saved · `3` could not write ·
`4` an agent could not complete

## Repository layout

```
src/main/java/ca/yorku/eecs3311/consultant/
  app/         JavaFX client - App, MainGUI, PaperFeedPanel
  cli/         Cli - the command-line client
  boot/        Bootstrap - the single composition root for both clients
  config/      Config - .env and environment settings
  controller/  AppController - the Facade, plus its two exceptions
  agent/       Agent and the six agents, Plan, ToolManager, the Observer, DigestBuilder
  analysis/    ComparabilityChecker, EvidenceRetriever, PaperParser, StrategyTaxonomy
  literature/  The LiteratureSource Strategy, both sources, SearchIntent, QueryTerms
  llm/         The LLMClient Adapter and ClaudeClient
  context/     The ProjectContextProvider Adapter and both providers
  memory/      ProjectMemory Singleton, MemoryStore, MemoryState
  repo/        GitHubClient
  domain/      The domain model, including the StrategyNode Composite
src/test/java/ 40 test classes and 2 fakes
docs/
  STAGE1.md            the Stage 1 design report
  STAGE2.md            the Stage 2 implementation report
  stage2-report.pdf    the same report, as submitted
  diagrams/            diagram sources (.mmd, .puml) and rendered images (.png)
    patterns/          one class diagram per design pattern
rc, rc-gui             run scripts for the CLI and the GUI
.env.example           safe example configuration, placeholders only
```
