# Roadmap

The prioritised queue governed by `spec/project/roadmap/`: highest priority at the top.
Items are added and retargeted by the `roadmap-plan` skill, never by hand; the
detail-level invariant is enforced by `roadmap-refine`. Every item carries its own inline
YAML block and cites at least one outcome from [`goals.md`](goals.md).

Item IDs are `R-<n>`, assigned monotonically and **never reused** across the project's
lifetime, even after an item is deleted or cancelled. The queue starts empty, so the first
item added will be `R-1`.

Phase headings below are documentation, not schema — they group the queue for reading and
carry no meaning for consuming skills. An item's position in the queue, not its phase,
expresses priority.

## Phase 1 — Connect

## Phase 2 — See your plants

## Phase 3 — Identify and record

## Independent — release readiness
