---
id: F-3
title: Act on a finding and revisit past detections
status: draft
roadmap_item: R-2
sprint: null
created: 2026-08-13
ended: null
verifies_sprint_value: null
consistency_check:
  - performed_at: 2026-08-12
    agent_version: feature-consistency-reviewer@4805a16
    findings:
      - kind: drift
        target: F-3 acceptance-3 (dropped the plant-bound clause of R23)
        resolution: proceed
      - kind: drift
        target: project/roadmap.md (mvp false, no mission file)
        resolution: revisit-after project/mission.md is authored
  - performed_at: 2026-08-13
    agent_version: feature-consistency-reviewer@92e056b
    findings:
      - kind: drift
        target: F-3 acceptance-4 (no host surface inside R-2's scope)
        resolution: revisit-after R-5 defines the plant detail surface
      - kind: drift
        target: F-3 acceptance-2 ↔ F-5 acceptance-3 (beneficial vs. create-inspection)
        resolution: proceed
---

## Description

A result is the beginning of a decision, not the end of one. The instance's identification is
an estimate, and the person holding the phone is standing in front of the actual plant — so
they are the one who can tell it whether it got the answer right, whether it confused a pest
with something harmless, or whether it missed entirely. That correction is what makes the
next identification better.

From a finding bound to a particular plant, the user can also turn the observation into an
IPM inspection, and keep the photograph they just took as part of that plant's record. The
detection endpoint deliberately keeps nothing — it hashes the image and forgets it — so
keeping the picture is always something the user asks for, never something that happens
because they looked.

## Acceptance criteria

- [ ] **acceptance-1** Each finding offers the three feedback actions, and the instance accepts the choice.
- [ ] **acceptance-2** A finding bound to a plant offers creating an IPM inspection; an unbound finding does not.
- [ ] **acceptance-3** For a plant-bound detection only, the captured image can be kept as a plant photo through an explicit action, and is never stored without one.
- [ ] **acceptance-4** Past detections for a plant can be reviewed.

## Test hooks

- **acceptance-1** — unit test over the feedback payload (`finding_label`, `confirmed`, `actual_label`, `was_beneficial`) — pending
- **acceptance-2** — unit test asserting the action is absent on an unbound detection — pending
- **acceptance-3** — unit test asserting no upload to `/plant-instances/{key}/photos` occurs without an explicit action, and that the action is absent on an unbound detection — pending
- **acceptance-4** — deferred: no host surface exists inside R-2's scope; see the consistency note — pending

## Consistency notes

**Pass 1 (2026-08-12).** The reviewer found that acceptance-3 had kept requirement R23's
explicit-action half but dropped its plant-bound half, which would have let the criterion
pass on an unbound detection that R23 excludes — and that is structurally impossible anyway,
since `/plant-instances/{key}/photos` needs a plant key. The contrast with acceptance-2,
which correctly carried both halves of its requirement, suggested an accidental omission
rather than a deliberate widening. The clause was restored.

**Pass 2 (2026-08-13) — acceptance-4 has no home.** The re-run found that requirement R24 is
confirmed and this criterion covers it, but the surface where per-plant detection history
would live is the plant detail screen, which this feature's own bounded context explicitly
places out of scope: "The plant **detail** screen (R-5); this feature needs only an entry
point from the plant list and a standalone entry." R-4 (plant list) and R-5 (detail) are both
unbuilt. The operator decided on 2026-08-13 to keep the criterion and record the dependency
rather than widen R-2's scope to host a history view — so **this feature cannot be fully
accepted until R-5 defines that surface**, and its test hook stays deferred until then. The
alternative considered was moving R24 out of this requirement artefact into R-5's; it was
rejected because the requirement was elicited and confirmed here.

**Beneficial findings and the inspection action.** Pass 2 found that acceptance-2 and F-5's
acceptance-3 can both hold while the product offers "create IPM inspection" on a finding the
instance matched as a beneficial — which R18 arguably forbids, since it says a beneficial
must never be presented as something to act against. The reviewer held this with deliberate
moderation rather than ruling on it, and the operator decided on 2026-08-13 that there is no
contradiction: an IPM inspection is neutral observation, not a treatment, consistent with the
bounded context's rule that detection never triggers one. Recorded so a future reader who
reaches the opposite conclusion can see the question was asked and answered rather than
missed.

**Carried forward.** R-2 remains `mvp: false` with no `project/mission.md`, which blocks
`ready → in_progress` but not `draft → ready`.

## Risks

- acceptance-4 depends on a surface that does not exist and is out of this feature's scope.
  It is the one criterion here that cannot be closed within R-2.
- The beneficial-vs-inspection decision is a judgement rather than a derivation; if it proves
  wrong in use, acceptance-2 and F-5's acceptance-3 are the pair to revisit together.
- Nothing here is implementable until R-1 delivers the generated client, which is itself
  blocked until backend release `v0.2.0` is published upstream.

## References

- `project/requirements/pest-detection.md` — R21, R22, R23, R24
- `project/roadmap.md` — R-5, which must define the surface acceptance-4 needs
- [#10](https://github.com/nolte/kamerplanter-android/issues/10) — the originating issue
