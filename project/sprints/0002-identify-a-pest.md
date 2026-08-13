---
number: 0002
status: planned
started: null
ended: null
value_statement: A plant owner photographs a suspected pest and their own instance tells them what it is.
artifact_ref: null
last_commit: null
roadmap_items: [R-2]
features: [F-1, F-2, F-3, F-4, F-5]
---

## Goal

This is the sprint the project exists for. A plant owner who spots something odd on a leaf
picks an image source — the phone camera for the damage pattern across the whole leaf, the
USB microscope for the animal itself — and their own instance answers: what it thinks this
is, where on the image it sits, and how sure it is. When it is not sure, it says so instead
of guessing, and when it recognises a beneficial insect it says not to treat it.

Closing this sprint closes the MVP. `project/mission.md` binds its own completion to
`F-2:acceptance-1`, the criterion this sprint ships, so the mission is achieved at the moment
this sprint's value is verified — the two are deliberately the same event.

**Dependencies, stated rather than discovered.** Every criterion here needs the generated API
client that sprint 1 delivers, so this sprint cannot start before sprint 1 closes. Sprint 1
in turn waits on backend release `v0.2.0` being published in `nolte/kamerplanter`. Nothing in
this sprint has been implemented; unlike sprint 1, it carries no regression contracts at all.

## Features

- [F-4](../features/gate-detection-availability.md) — status: ready
- [F-1](../features/capture-pest-image.md) — status: ready
- [F-2](../features/receive-pest-identification.md) — status: ready — **carries `verifies_sprint_value: acceptance-1`**
- [F-5](../features/read-detection-findings.md) — status: ready
- [F-3](../features/act-on-detection-finding.md) — status: ready

## Out of scope

- Everything roadmap item R-1 covers — connecting to an instance at all. That is sprint 1,
  and this sprint depends on it rather than repeating it.
- Detection history per plant. Requirement R24 moved out of F-3 to roadmap item R-5: the
  history needs the plant detail surface, which R-2's own bounded context excludes, and
  keeping the criterion here made R-2 unable to ever reach `done`.
- The plant list and detail screens — roadmap items R-4 and R-5.
- The 4 KB page-alignment defect — roadmap item R-3,
  [#14](https://github.com/nolte/kamerplanter-android/issues/14).

## Review notes

_Populated by `sprint-review` at closure._
