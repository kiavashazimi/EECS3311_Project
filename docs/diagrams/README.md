# Diagrams

Mermaid (`.mmd`) and PlantUML (`.puml`) sources, each with its rendered `.png`. The PNG is
the image of record: GitHub renders neither source format.

## Class diagrams

`class-diagram.mmd` is the **Stage 1 design** diagram, embedded byte-identical in
`../STAGE1.md` section 3 and left exactly as submitted on Oct 3.

The **Stage 2 as-built** structure is four views. The implementation is 55 classes, which
is more than one readable sheet holds, so each view carries one concern and says in its
header comment where the rest lives.

| View | Source | Shows |
|---|---|---|
| 1 | `class-diagram-stage2-app.mmd` | Clients, the `AppController` Facade, the `PaperFeedListener` Observer, the `ProjectMemory` Singleton and its persistence |
| 2 | `class-diagram-stage2-agents.mmd` | The `Agent` Template Method hierarchy and its six subclasses |
| 3 | `class-diagram-stage2-services.mmd` | Everything behind `ToolManager`: the `LiteratureSource` Strategy, both Adapters, the deterministic analysis services, and the checked exceptions |
| 4 | `class-diagram-stage2-domain.mmd` | The domain model, including the `StrategyNode` Composite |

Together the four carry every class in `src/main/java`, plus `GroundedAnswerAgent.Mode`
and `ProjectContextAgent.ImportedContext`, which appear in public signatures.
`ToolManager` and `Config` appear in two views each, as the seam between them.

Reading conventions, stated on the diagrams themselves:

- Getters, setters and `equals`/`hashCode` are omitted everywhere.
- The six agents all override `plan`, `executeTools` and `summarize` and nothing else, so
  those three are shown on `Agent` and not repeated in each subclass.
- A box with no members is detailed in another view.

## Sequence diagrams

`sd01`–`sd09`, one per Stage 1 sequence diagram, in `../STAGE1.md` section 5.

## Other

`use-case-diagram.puml` is the Stage 1 use-case diagram. `patterns/` holds one small
diagram per design pattern, from `../STAGE1.md` section 4.

Rendering instructions and the traps involved are in `DIAGRAMS.md` in the parent working
folder, which is not part of this repository.
