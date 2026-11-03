# Test fixtures

Fake project directories for `ProjectContextProvider` tests. All fictional: a
test must not depend on a real project, which may live on another machine and
may contain unpublished work.

| Fixture | `isAvailable()` | What it covers |
|---|---|---|
| `agent-project/` | true | a project with a full `CLAUDE.md` — aims, datasets, models, metrics |
| `thin-agent-project/` | true | `CLAUDE.md` exists but says nothing about the project; the description must come back weak rather than invented |
| `plain-project/` | false | no agent context, so `ProjectContextAgent` falls back to `FileBasedContextProvider` — the `else` branch of SD02 |
