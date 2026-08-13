---
id: F-1
title: Capture a pest image from phone camera or microscope
status: ready
roadmap_item: R-2
sprint: 2
created: 2026-08-13
ended: null
verifies_sprint_value: null
consistency_check:
  - performed_at: 2026-08-12
    agent_version: feature-consistency-reviewer@4805a16
    findings:
      - kind: prior-art
        target: feature/microscope/.../UvcMicroscopeCamera.kt:229
        resolution: proceed
      - kind: drift
        target: F-1 acceptance-5 (dropped)
        resolution: proceed
      - kind: prior-art
        target: worktree connection — feature/settings/
        resolution: revisit-after feat/backend-connection merges to develop
      - kind: drift
        target: F-1 acceptance-4 (8 MB at an internal boundary)
        resolution: merge-into F-2
  - performed_at: 2026-08-13
    agent_version: feature-consistency-reviewer@92e056b
    findings:
      - kind: overlap
        target: F-4 acceptance-4 ↔ F-1 acceptance-1
        resolution: proceed
      - kind: drift
        target: F-1 (phone-camera capture uncovered)
        resolution: proceed
      - kind: prior-art
        target: feature/microscope/.../UvcMicroscopeCamera.kt:69-86
        resolution: proceed
      - kind: drift
        target: F-1 acceptance-5 (unfalsifiable wording)
        resolution: proceed
---

## Description

Before anything can be identified, the user has to point something at the problem. This
feature is that choice and its consequence: the phone camera for the damage pattern across
a whole leaf, the USB microscope for the animal itself when it is too small for a phone to
resolve. The microscope only offers itself when one is actually plugged in.

Both sources end at the same place — one image, in memory, ready to be sent. What differs
is only where the pixels come from. The microscope path takes its still at the largest mode
the device offers and puts the live preview back afterwards, so the shutter moment buys
detail without leaving the user staring at a frozen screen.

## Acceptance criteria

- [ ] **acceptance-1** The user chooses between phone camera and USB microscope before capturing.
- [ ] **acceptance-2** The microscope option is offered only while a UVC device is attached.
- [ ] **acceptance-3** A capture from the phone camera produces an image that enters the same detection pipeline as a microscope capture.
- [ ] **acceptance-4** A microscope still is captured at the largest mode the device offers, and the live preview returns afterwards.
- [ ] **acceptance-5** When the microscope seam reports the device unavailable, the detection capture action stops being offered rather than remaining live.

## Test hooks

- **acceptance-1** — unit test over the source-picker state in the detection feature module — pending
- **acceptance-2** — manual on the Pixel 7a, attaching and detaching the reference microscope (`1b3f:2002`) — pending
- **acceptance-3** — unit test that both sources yield the same in-memory image type; manual capture on the Pixel 7a — pending
- **acceptance-4** — manual on the Pixel 7a, comparing captured dimensions against the preview's; `UvcMicroscopeCamera.grabStill()` already implements this, so the hook is a regression check rather than new verification — pending
- **acceptance-5** — unit test driving `MicroscopeState.Unavailable(NO_DEVICE_ATTACHED)` into the detection view model — pending

**Deliberately criterion-free requirements.** R11 (the shared camera module) and R12 (the
ADR 0001 isolation rule) carry no acceptance criterion and therefore no hook — a hook keyed
to a requirement rather than an `acceptance-<n>` could never move from `pending` to
`passing`. Their assurance rides elsewhere: R11's user-visible consequence is acceptance-3,
and the existing `QrPayloadParserTest` and `SettingsViewModelTest` guard that QR scanning
survives the move; R12 is architecture enforcement — no reference to `libuvc` outside
`feature/microscope/` — verified by review rather than by a test.

## Consistency notes

**Pass 1 (2026-08-12).** Four findings landed here. The reviewer found that
`UvcMicroscopeCamera.grabStill()` already retunes to `modes.largest()` and restores the
preview, which falsified requirement R9 and reduced R8 to an observation of shipped
behaviour; the requirement artefact now carries that correction and the stale interface
KDoc that caused it is tracked as issue #15. The original acceptance-5 — asserting the
pairing QR still scans after the camera capability moved — was identified as a refactoring
gate in disguise: it asserts the *absence* of change, its subject is R-1's user surface
rather than R-2's, and R44 in the connection requirement artefact already guarantees it. It
was dropped and re-expressed as a test hook above. The 8 MB / MIME criterion was found to
sit at an internal handoff boundary with no user-visible manifestation and was merged into
F-2's end-to-end criterion, with the failure path becoming its own criterion there. Finally,
the shared-camera move was flagged as colliding with in-flight work on
`feat/backend-connection`, resolved as `revisit-after` that branch merges.

**Pass 2 (2026-08-13).** The re-run caught a gap the first pass could not have seen. Once
R11 was corrected — `:feature:settings` binds only `Preview` and `ImageAnalysis`, so the
still capture is new code rather than relocated code — dropping the old acceptance-5 left
the phone-camera path with no coverage at all: the feature asserted that the user *chooses*
the phone camera but never that choosing it yields an image. acceptance-3 was added to
cover that user-visible consequence without naming a module, which would have reinstated the
refactoring gate.

The reviewer also found that R28's substance already ships: `onLost` closes the stream when
the lost device is ours, `Unavailable(NO_DEVICE_ATTACHED)` models it, and
`MicroscopeViewModel.retry()` is documented as recovering "without making the user unplug
the microscope". The criterion was rescoped to what is genuinely new — the detection
screen's capture action must not stay live — and its earlier wording ("a state they can
recover from") was rejected as unfalsifiable, since any implementation passes it. The
provenance is worth recording: R28 is `assumed`, not teach-back-confirmed.

**Overlap requiring rationale — F-4 acceptance-4 ↔ F-1 acceptance-1, resolution `proceed`.**
F-4 asserts that no image is captured until cloud consent is obtained, which constrains the
very moment this feature owns. The overlap is intrinsic rather than accidental: requirement
R3 deliberately places the consent gate *before capture*, "never merely before upload",
because an image that was never taken cannot leak. Merging the consent surface into this
feature would scatter a GDPR-bearing interaction across two features and destroy F-4's
cohesion as the gating feature; splitting it further would create a feature for a single
criterion. The honest resolution is to record the coupling: **F-1 is not independently
acceptable**, and F-4 must be implemented before or alongside it. Nothing in F-1's own
criteria contests F-4 — the sources this feature offers are gated on device attachment,
while F-4 gates on instance configuration, which are different objects with different
authorities.

## Risks

- The phone-camera still capture is new code, not a relocation. Requirement R11's original
  wording implied otherwise and was corrected on 2026-08-13.
- Moving the shared camera capability out of `:feature:settings` touches a module that
  `feat/backend-connection` is actively rewriting. Sequencing this after that branch merges
  avoids a conflict the requirement artefact records explicitly.
- acceptance-5 rests on requirement R28, which is `assumed` rather than confirmed by
  teach-back.
- Nothing in this feature is implementable until R-1 delivers the generated API client, and
  R-1 is itself blocked until backend release `v0.2.0` is published upstream.

## References

- `project/requirements/pest-detection.md` — R6, R7, R8, R11, R12, R28
- [#10](https://github.com/nolte/kamerplanter-android/issues/10) — the originating issue
- [#15](https://github.com/nolte/kamerplanter-android/issues/15) — the stale `MicroscopeCamera` KDoc that caused the R8/R9 error
- `docs/en/adrs/0001-tech-stack.md` — the UVC isolation rule
