# CLAUDE.md

Operating guide for this project. Fictional — a test fixture for
`ClaudeCodeContextProvider`, not a real piece of research.

## What this project is

Detecting anomalous energy use in commercial building HVAC telemetry, and asking
whether a model trained on well-instrumented buildings transfers to poorly
instrumented ones.

**Research question.** Does adding synthetic sensor dropout during training make
an anomaly detector robust to buildings whose telemetry is sparse, without
costing accuracy on buildings where it is complete?

**Datasets.** LBNL-59 (59 buildings, 1-minute resolution), BDG2 (Building Data
Genome 2), and an internal campus feed held back as an unseen test site.

**Models.** A temporal convolutional autoencoder baseline, and the same model
trained with injected dropout. A seasonal-naive detector is the floor.

**Metrics.** Precision at 100 alerts, event-level recall, and the gap between
well- and poorly-instrumented buildings. The gap is the number that matters;
mean performance hides it.

## Status

Baselines reproduce on LBNL-59. Dropout injection is implemented and running on
BDG2. The campus feed has not been touched yet and must stay held back.

## Layout

```
configs/       one YAML per run
data/          symlinks to the shared store, never copied
train.py       entry point
evaluate.py    writes per-building metrics to csv_results/
```

## Conventions

- Every run writes a config snapshot beside its results; a result without one is
  not reportable.
- Never train on the campus feed. It is the only unseen site left.
- Seeds are fixed in the config, not on the command line.
