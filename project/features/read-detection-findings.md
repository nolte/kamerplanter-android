---
id: F-5
title: Read what the instance found
status: ready
roadmap_item: R-2
sprint: 2
created: 2026-08-13
ended: null
verifies_sprint_value: null
consistency_check:
  - performed_at: 2026-08-13
    agent_version: feature-consistency-reviewer@92e056b
    findings:
      - kind: overlap
        target: F-2 acceptance-1 ↔ F-5
        resolution: proceed
      - kind: drift
        target: F-5 acceptance-5 (unfalsifiable wording)
        resolution: proceed
      - kind: drift
        target: F-3 acceptance-2 ↔ F-5 acceptance-3 (beneficial vs. create-inspection)
        resolution: proceed
---

## Description

An identification is only worth having if the person reading it can tell what they are
looking at. This feature owns that: where on the leaf the finding sits, whether it is the
animal itself or the damage it left behind, and how confident the instance is.

Two of its states matter more than the happy path. A finding the instance matched as a
**beneficial** is shown as one, with an explicit note not to treat it — mistaking a
predatory mite for a pest and spraying it is the worst outcome this feature can produce.
And when the instance is not confident, it says so instead of guessing: an unconfident result
renders as "no reliable detection — inspect manually", never as a thin list of maybes. The
instance's disclaimer travels with every result and is shown word for word, because the app
must not turn an estimate into something that reads like a diagnosis.

## Acceptance criteria

- [x] **acceptance-1** A finding's marked region sits over the right part of the captured image, with coordinates read as normalized `0..1` of the full image.
- [ ] **acceptance-2** A finding states whether it is the animal itself or the damage pattern it left.
- [ ] **acceptance-3** A finding matched as a beneficial carries an explicit do-not-treat note and never appears as something to act against.
- [x] **acceptance-4** An unconfident result renders the abstention state instead of a findings list.
- [x] **acceptance-5** A confident result carrying no findings renders an explicit "nothing found" state, worded distinctly from the abstention state.
- [ ] **acceptance-6** The instance's disclaimer appears verbatim, with no paraphrase.

## Test hooks

- **acceptance-1** — `OverlayGeometryTest` — five cases over `overlayRect`, including letterboxed and pillarboxed fits and a subsampled bitmap
- **acceptance-2** — screen-level `mode` mapping in place; no assertion yet — pending
- **acceptance-3** — `NetworkPestDetectionClientTest` pins `isBeneficial` off `matched_beneficial_key` rather than the category string; the do-not-treat note itself is screen-level — pending
- **acceptance-4** — `DetectionShapeTest` — `is_confident: false` abstains, and still abstains when findings are listed below the threshold
- **acceptance-5** — `DetectionShapeTest` — `is_confident: true` with an empty `findings` array is a distinct `NOTHING_FOUND` outcome, worded separately from the abstention
- **acceptance-6** — the `disclaimer` is rendered from the response with no transformation; no assertion yet — pending

## Consistency notes

This feature did not exist during pass 1. It was created after that pass warned that F-2
would carry ten criteria once the coverage gaps were closed — well past the spec's
three-to-seven guidance and into its explicit split signal. The natural seam was between
getting a result and reading one.

**Overlap requiring rationale — F-2 acceptance-1 ↔ this feature, resolution `proceed`.**
F-2's end-to-end criterion ends by asserting that a finding is listed on the phone, which is
this feature's surface. Pass 2 examined whether the split had merely relocated the ownership
problem it solved in pass 1 and concluded it had not: this feature never asserts *that* a
finding appears, only *what* a finding must convey. F-2 establishes the happy path; the
criteria here own its content and its two exception branches. Merging them would undo a
split made for a defensible reason and push this feature past the criterion guidance again;
ending F-2's criterion at the response boundary would leave F-2 with no user-visible
terminus, which the spec forbids. F-2's display claim is deliberately minimal — "at least one
finding is listed" — so it cannot absorb the semantics owned here.

**Falsifiability.** acceptance-5 originally read "is distinguishable from the abstention
state", which any implementation passes by assertion; the spec requires a criterion a
reviewer can mark done or not done without ambiguity. It now names an observable — an
explicit "nothing found" state with wording distinct from the abstention text. Its
provenance is worth recording: requirement R29 is `assumed`, derived from the response shape
rather than confirmed by teach-back. Criterion-level coverage of an assumed requirement is
sound; hiding the assumption inside vague wording would not have been.

**Beneficial findings and the inspection action.** Pass 2 found that this feature's
acceptance-3 and F-3's acceptance-2 can both hold while the product offers "create IPM
inspection" on a finding matched as a beneficial. The operator decided on 2026-08-13 that
this is not a contradiction: an IPM inspection is neutral observation rather than acting
against the organism, consistent with the bounded context's rule that detection never
triggers a treatment. Recorded here so the reasoning survives, and so a future reader who
reaches the opposite conclusion knows the question was asked rather than missed.

## Risks

- The generated OpenAPI client cannot carry the bounding box's `0..1` contract: the
  backend's domain model constrains and documents it, but the API DTO declares bare floats
  with neither. acceptance-1 therefore rests on the app asserting the normalisation itself,
  and a future reader could plausibly misread the values as pixels.
- acceptance-5 rests on requirement R29, which is `assumed` rather than confirmed.
- The beneficial-vs-inspection decision above is a judgement, not a derivation; if it proves
  wrong in use, acceptance-3 and F-3's acceptance-2 are the two criteria to revisit together.
- Nothing here is implementable until R-1 delivers the generated client. The upstream half of
  that dependency cleared on 2026-08-13, when release `v0.2.0` was published with its
  `openapi.json` asset; generating the client from it is R-1's own work.

## References

- `project/requirements/pest-detection.md` — R16, R17, R18, R19, R20, R29
- [#10](https://github.com/nolte/kamerplanter-android/issues/10) — the originating issue
