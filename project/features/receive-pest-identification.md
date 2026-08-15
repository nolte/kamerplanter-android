---
id: F-2
title: Receive an identification for a captured image
status: ready
roadmap_item: R-2
sprint: 2
created: 2026-08-13
ended: null
verifies_sprint_value: acceptance-1
consistency_check:
  - performed_at: 2026-08-13
    agent_version: feature-consistency-reviewer@4805a16
    findings:
      - kind: overlap
        target: F-2 ↔ F-1 (capture-action ownership)
        resolution: split-out F-4
      - kind: drift
        target: F-2 (criterion atomicity)
        resolution: proceed
      - kind: drift
        target: project/requirements/pest-detection.md (coverage gap R5, R15, R17, R27, R29)
        resolution: proceed
  - performed_at: 2026-08-13
    agent_version: feature-consistency-reviewer@92e056b
    findings:
      - kind: overlap
        target: F-2 acceptance-1 ↔ F-5
        resolution: proceed
      - kind: drift
        target: F-2 acceptance-1 (re-bundled by the R10 fold)
        resolution: proceed
      - kind: drift
        target: F-2 (phone-camera branch had no end-to-end criterion)
        resolution: proceed
      - kind: drift
        target: project/roadmap.md R-2 (detail coarse, status proposed, mvp false, no mission)
        resolution: revisit-after project/mission.md exists
---

## Description

This is the round trip the whole app exists for: the captured image goes to the instance the
user runs themselves, that instance identifies what is on it, and the answer comes back to
the phone. Nothing is recognised on the device — the model, the tiling and the confidence
threshold all stay on the server, and the app's job is to get the bytes there and the answer
back intact.

Both image sources travel the same path. A capture that started from a particular plant is
identified against that plant, which is what later makes history and an IPM inspection
possible; a standalone capture is identified without any such binding. The response comes
back in the language the app's own interface speaks, so a finding's name and the instance's
disclaimer are readable to the person holding the phone.

## Acceptance criteria

- [ ] **acceptance-1** An image captured through the USB microscope at the device's largest offered mode is identified by the connected instance, and at least one finding is listed on the phone.
- [x] **acceptance-2** An image captured with the phone camera is identified the same way.
- [ ] **acceptance-3** A capture started from a plant is identified against that plant; a standalone capture is not bound to one.
- [ ] **acceptance-4** Findings and the disclaimer come back in the language the app's interface speaks, not the raw device locale.
- [ ] **acceptance-5** When the instance rejects the image, the message names the formats it accepts.

## Test hooks

- **acceptance-1** — `NetworkPestDetectionClientTest` covers the upload and the response mapping; the end-to-end capture against a live instance is still outstanding — pending
- **acceptance-2** — `PestDetectionViewModelTest` — a phone capture reaches the same upload as a microscope one, and one that cannot be brought under the limit fails before spending it
- **acceptance-3** — `NetworkPestDetectionClientTest` pins that a plant key routes to the plant-bound endpoint and its absence to the standalone one; the app only reaches the standalone path so far — pending
- **acceptance-4** — `PestDetectionViewModelTest` pins the language reaching the client, and `NetworkPestDetectionClientTest` that it arrives as an unquoted form field; the screen takes it from the configuration that resolved its own strings — pending
- **acceptance-5** — the unsupported-media-type message names JPEG and PNG; no assertion yet — pending

**Deliberately criterion-free requirements.** R10 (the 8 MB and MIME limits) and R25 (EN/DE
resources) carry no acceptance criterion and therefore no hook — a hook keyed to a
requirement rather than an `acceptance-<n>` could never move from `pending` to `passing`. The
upload limit is an internal boundary whose observable consequence is acceptance-1 failing if
the app neglects to downscale; the locale coverage is verified by the project's localisation
check.

## Consistency notes

**Pass 1 (2026-08-12).** The reviewer found that this feature and F-1 both owned whether
the capture action is presented — F-1 asserting the picker appears, F-2 asserting conditions
under which it must not — leaving neither independently verifiable. The availability and
consent gate was split out as F-4, which also gave requirement R5 a home it previously
lacked. Three criteria were found non-atomic (each carrying two independently failable
checks) and were split. A coverage audit found five confirmed requirements with no criterion
anywhere in the decomposition; R15, R17, R27 and R29 were added here or on F-5, and R5 went
to F-4.

**Pass 2 (2026-08-13).** The re-run found that pass 1's own recommendations had been in
tension: folding R10 into the end-to-end criterion re-bundled the very criterion the
atomicity finding had just unbundled. acceptance-1 was split so that the round trip and the
minimal display claim are separable, and the phone-camera branch — which had no end-to-end
criterion at all, since acceptance-1 named only the microscope — gained acceptance-2.

On the 8 MB question specifically, the reviewer advised against restoring a criterion naming
the limit: it is an internal boundary, and acceptance-1 already fails if the app neglects to
downscale, because the instance rejects the upload. One residual oddity is recorded rather
than resolved: acceptance-5 tests a rejection path that R10 declares unreachable, and nothing
covers a `413`.

**Overlap requiring rationale — F-2 acceptance-1 ↔ F-5, resolution `proceed`.** acceptance-1
ends by asserting that a finding is listed on the phone, which is F-5's surface. Merging the
two would undo a split made for a defensible reason and push F-5 past the spec's
seven-criterion guidance. Ending acceptance-1 at the response boundary instead — "the app
receives the result" — would be worse: the spec requires acceptance criteria to be
user-visible behaviour, and a feature whose terminus is invisible stops being a feature at
all. The seam holds because F-5 never asserts *that* a finding appears, only *what* a
finding must convey; F-2 establishes the happy path and F-5 owns its content and its
exception branches. acceptance-1's display claim is deliberately minimal — "at least one
finding is listed" — so it cannot be read as absorbing F-5's semantics.

**Carried forward, now resolved.** The earlier passes recorded that R-2 sat at
`detail: coarse` / `mvp: false` with no `project/mission.md`, which blocked
`ready → in_progress` but not `draft → ready`. Both conditions have since changed in the
same planning layer that carries this file: `requirements-elicit` has run for R-2 at
`U_gate 0.85`, so `roadmap-plan` promoted it to `detail: fine` with `mvp: true` and
`target_sprint: 2`, and `project/mission.md` now exists.

**Why `verifies_sprint_value` names acceptance-1.** `project/mission.md` names
`F-2:acceptance-1` as its `verifies_via`, which makes this the **mission-verifying feature**
for the whole MVP; the mission spec requires exactly one such feature across the MVP scope.
The field was set while this feature was still unscheduled, so that the criterion was
nameable before sprint 2 existed — now that sprint 2 carries F-2, it is also the criterion
that sprint's value contract points at.

## Risks

- The generated OpenAPI client cannot express the bounding box's `0..1` contract, because
  the API DTO drops the constraints and docstring the backend's domain model carries. The
  app must assert the normalisation itself; a future reader could plausibly misread the
  values as pixels. This bites F-5 rather than F-2, but originates in the client this
  feature consumes.
- `acceptance-5` covers `415` but nothing covers `413`; whether the instance distinguishes
  them was not verified.
- Nothing here is implementable until R-1 delivers the generated client. The upstream half of
  that dependency cleared on 2026-08-13, when release `v0.2.0` was published with its
  `openapi.json` asset; generating the client from it is R-1's own work.

## References

- `project/requirements/pest-detection.md` — R10, R13, R15, R25, R26, R27
- `project/goals.md` — O-2, the outcome `acceptance-1` verifies
- [#10](https://github.com/nolte/kamerplanter-android/issues/10) — the originating issue
- `spec/api/openapi-client-integration/en.md` — the client this feature consumes
