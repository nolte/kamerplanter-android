# Requirements — Pest detection from a microscope or phone photo

<!--
Produced via the `requirements-elicit` skill, following
spec/project/requirements-elicitation/ (canonical spec resolved from the shared
claude-shared repo; not vendored in this project).
`c_d` is an uncertainty proxy (self-consistency-derived), not a calibrated
probability. A requirement is `confirmed` only after an explicit teach-back.

Elicited for roadmap item R-2 (project/roadmap.md), serving outcome O-2. Implements
https://github.com/nolte/kamerplanter-android/issues/10 and closes the open upload half of
https://github.com/nolte/kamerplanter-android/issues/1.
-->

## Bounded context

- **What:** the app-side pest-detection flow — a source picker (phone camera or USB
  microscope), the upload, rendering the result, the human-in-the-loop feedback, creating
  an IPM inspection from a finding, and detection history for a plant. Recognition itself
  stays in the backend **always**; the app is a capture-and-render client. No model, no
  tiling, no threshold and no abstention decision lives on the device.
- **For whom:** the plant owner who spotted something odd on a leaf and wants the question
  settled where the plant stands. Secondarily the self-hoster, whose instance configuration
  decides whether the feature is available at all and which adapter answers.
- **Out of scope:**
  - Any on-device inference.
  - IPM treatment records beyond creating the inspection — the user guide is explicit that
    detection never triggers a treatment.
  - The plant **detail** screen (R-5); this feature needs only an entry point from the
    plant list and a standalone entry.
  - Choosing or configuring the detection adapter — server-side admin work.
  - Requesting an upstream image-provenance field (see R-14).

**Dependency, deliberately elicited ahead of implementability:** this feature consumes the
connection and the generated API client from R-1, which is itself blocked until backend
release `v0.2.0` is published upstream. The requirements are captured now because the
project mission points at this feature via `verifies_via`, and because decomposing against
unelicited requirements is forbidden.

## Understanding KPI

- Thresholds: `τ_low = 0.4`, `τ_high = 0.8`, self-consistency `k = 2`, question budget = `6` (spec defaults; unchanged).
- Question turns spent: 3 decision turns (each a tightly-coupled group) + 1 teach-back = **4 / 6**. One of those turns — the capture-resolution question — turned out to be moot; see the correction record.
- `U_gate = min_d c_d` over required dimensions = **0.85**, unchanged after the 2026-08-13 correction. The two affected cells were re-evidenced rather than merely re-scored: understanding of the capture path is now grounded in the implementation and an adversarial review, which is stronger evidence than the interface KDoc that originally justified them.
- Termination: `saturation` (`min_d c_d ≥ τ_high`; no remaining question carries positive net EVPI — the residuals below are resolvable by action or by an upstream decision, not by another question to the operator).

### Gap matrix

| Dimension | Applicable | `c_d` | Uncertainty source | Evidence event |
|---|---|---|---|---|
| `functional` | yes | 0.90 | specification (resolved) | Q1–Q6 across three decision turns; the issue's own verified endpoint table; teach-back confirmed |
| `non_functional` | yes | 0.85 | interpretation (**re-evidenced**) | the 8 MB / MIME upload contract; the capture-resolution question is settled by `UvcMicroscopeCamera.grabStill()` rather than by Q1, which was moot |
| `constraints` | yes | 0.85 | interpretation (**re-evidenced**) | CLAUDE.md + ADR 0001 + Q2; the original cell cited "`MicroscopeCamera` read directly" and that reading was of the interface's stale KDoc. Re-grounded against the implementation and against `feature/settings/` — see the correction record below |
| `domain_objects` | yes | 0.90 | specification (resolved) | response shape and bounding-box semantics verified against the backend domain model, not inferred from prose |
| `actors` | yes | 0.90 | specification (resolved) | bounded context + teach-back; the self-hoster's configuration is what gates availability |
| `acceptance_criteria` | yes | 0.85 | specification (resolved) | derived from the issue's in-scope list, narrowed by Q1–Q6; teach-back |
| `edge_cases` | yes | 0.85 | specification (resolved) | upstream status/consent gating, abstention, MIME and size limits, mid-flow device detach; teach-back |
| `scope_boundaries` | yes | 0.90 | specification (resolved) | the issue's explicit out-of-scope list, extended by Q6's provenance decision; teach-back |

Self-consistency (`k ≥ 2`) was decisive on two dimensions:

- **`non_functional` — microscope capture resolution. This deliberation was invalid; it is
  kept as an audit trail rather than deleted.** Two readings were weighed — one 1080p frame
  from the running stream versus reconfiguring to 4K for the shutter moment — and the
  operator was asked to choose, picking 4K. The premise was false: the shipped
  implementation already retunes to the sensor's largest mode and restores the preview, so
  the question had been answered in code before it was asked. See the correction record
  below.
- **`constraints` — where the shared camera capability lives.** Three readings: leave
  CameraX in `:feature:settings` and duplicate, move it to a common module, or give the
  detection feature its own. The operator chose the common module.

## Requirements

<!-- EARS/CNL form; each tagged confirmed/assumed with traceability. -->

### Availability gating

- **R1** — WHEN the user opens the detection entry point, the app SHALL call
  `GET /api/v1/t/{tenant_slug}/pest-detection/status` and resolve availability from
  `available`, `feature_enabled` and the per-adapter `configured` before offering any
  capture action.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #10 §1; teach-back
- **R2** — WHILE the feature is unavailable or no adapter is configured, the app SHALL NOT
  present a capture action, and SHALL instead explain why the feature cannot be used.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #10 ("never offer a button that 4xx's"); teach-back
- **R3** — WHEN the active adapter declares `requires_consent: "pest_detection_cloud"` and
  that consent is not yet granted, the app SHALL obtain it **before any image is
  captured**, never merely before upload.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #10 §1; teach-back
- **R4** — The app SHALL obtain that consent in-app via `/api/v1/privacy/consents`,
  displaying the purpose label, description and legal basis **verbatim as the server
  supplies them**, and SHALL NOT author its own consent wording.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: Q5 = "In der App einholen, über die Privacy-Endpunkte"; teach-back
- **R5** — The app SHALL NOT require consent on the self-hosted detection path; the
  `pest_detection_cloud` purpose applies only while a cloud adapter is active.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: backend `consent_engine.py` ("the self-hosted path needs no such consent"); teach-back

### Capture

- **R6** — The app SHALL offer exactly two image sources — phone camera and USB microscope
  — feeding one shared detection pipeline.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #10 §2; teach-back
- **R7** — The microscope source SHALL be offered only WHILE a UVC device is actually
  attached.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #10 §2; teach-back
- **R8** — WHEN the user captures from the microscope, the capture SHALL be taken at the
  largest mode the device offers, and the preview SHALL return to its negotiated mode
  afterwards. **Corrected 2026-08-13: this already holds in the shipped implementation.**
  `UvcMicroscopeCamera.grabStill()` retunes the running stream to `modes.largest()` and
  restores the preview in a `finally` block, so the requirement records existing behaviour
  rather than work to be done. Note that "largest mode" is deliberately not "4K": the
  implementation avoids a hard-coded resolution so a device with a smaller maximum is not
  stranded at a few frames per second.
  - _dimension_: `non_functional` · _status_: `confirmed` (as an observation of shipped code) · _source_: `UvcMicroscopeCamera.kt:229–245`, `StreamSession.kt:52–75`
- **R9** — **Withdrawn 2026-08-13.** This requirement stated that the `MicroscopeCamera`
  seam must be extended to express a capture resolution, because "it exposes none today,
  and `captureFrame()` currently grabs from a stream fixed at 1920×1080". Both halves are
  false: `grabStill()` already retunes to the sensor's full resolution and restores the
  preview, and `StreamSession` carries `retune()` / `restorePreview()` for exactly that.
  The error came from reading the `MicroscopeCamera` *interface* — whose KDoc still says
  "MJPEG, captured at 1920x1080" — instead of the implementation. The stale KDoc is a
  separate defect and is not this feature's to fix.
  - _dimension_: `constraints` · _status_: `withdrawn` · _source_: refuted by `feature-consistency-reviewer` during the R-2 decomposition; verified independently against `UvcMicroscopeCamera.kt`
- **R10** — The app SHALL ensure every uploaded image stays within the backend's 8 MB
  limit and is `image/jpeg` or `image/png`, re-encoding or downscaling where the captured
  frame would exceed it — which the 4K microscope frame may.
  - _dimension_: `non_functional` · _status_: `confirmed` · _source_: issue #10 upload contract; teach-back
- **R11** — The device-camera capability SHALL live in a module that both the QR scanner
  and this feature's still capture consume, rather than inside `:feature:settings`.
  **Corrected 2026-08-13:** the original wording said the "CameraX still capture, plus the
  existing QR scanning" would move. Only the QR scanning exists — `:feature:settings` binds
  `Preview` and `ImageAnalysis` and carries no `ImageCapture` or `takePicture` anywhere.
  The still capture is therefore new code written in the shared module, not something
  relocated.
  - _dimension_: `constraints` · _status_: `confirmed` · _source_: Q2 = "In ein gemeinsames Modul ziehen"; teach-back; premise corrected against `feature/settings/` directly
- **R12** — The UVC engine SHALL remain inside `:feature:microscope` and SHALL be consumed
  only through the app-owned `MicroscopeCamera` interface; the detection feature SHALL NOT
  reference `libuvc`.
  - _dimension_: `constraints` · _status_: `confirmed` · _source_: ADR 0001 isolation rule; teach-back

### Upload

- **R13** — The app SHALL send the image as `multipart/form-data` in the field `image`,
  with a `language` form field carrying the **resolved resource locale** (`en` or `de`) —
  never the raw device locale — so findings and the disclaimer return in the language the
  UI itself speaks.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: Q3 = "Aufgelöste Ressourcensprache (en oder de)"; teach-back
- **R14** — The app SHALL track the image's provenance (microscope or phone camera)
  **locally only**, and SHALL NOT request an upstream provenance field; neither
  `PestDetectionSource` nor `PestDetectionTrigger` can express it.
  - _dimension_: `scope_boundaries` · _status_: `confirmed` · _source_: Q4 = "Nur lokal mitführen, kein Upstream-Wunsch"; teach-back
- **R15** — WHEN the capture is bound to a plant, the app SHALL post to
  `POST /plants/{plant_key}/detect`; otherwise to `POST /detect`.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #10 §3; teach-back

### Rendering the result

- **R16** — The app SHALL draw each `bounding_box` over the captured image, treating
  `x`/`y`/`width`/`height` as **normalized 0–1 values in the full-image coordinate
  system**, labelled with `common_name` and the confidence as a percentage.
  - _dimension_: `domain_objects` · _status_: `confirmed` · _source_: backend `pest_detection_adapter.py` ("Normalisierte Box (0–1) im Vollbild-Koordinatensystem", `ge=0.0, le=1.0`); teach-back
- **R17** — The app SHALL distinguish a detected animal from a damage pattern using
  `mode`.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #10 §4; teach-back
- **R18** — WHEN a finding carries `matched_beneficial_key`, the app SHALL present it as a
  beneficial with an explicit "do not treat" note, and SHALL NEVER present it as something
  to act against.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #10 §4; teach-back
- **R19** — WHEN `is_confident` is `false`, the app SHALL render the abstention state
  ("no reliable detection — inspect manually") instead of a findings list, and SHALL NOT
  treat it as an error.
  - _dimension_: `edge_cases` · _status_: `confirmed` · _source_: issue #10; teach-back
- **R20** — The app SHALL display the response's `disclaimer` **verbatim** and SHALL NOT
  paraphrase it into anything that reads like a diagnosis.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #10 §4; teach-back

### After the result

- **R21** — Each finding SHALL offer the three feedback actions, posting `finding_label`,
  `confirmed`, `actual_label` and `was_beneficial` to
  `POST /detections/{detection_key}/feedback`.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #10 §5; teach-back
- **R22** — WHEN the detection is bound to a plant, the app SHALL offer "create inspection"
  via `POST /detections/{detection_key}/create-inspection`; WHEN it is unbound, it SHALL
  NOT.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #10 §3, §5; teach-back
- **R23** — The app MAY offer, as an **explicit user action** and only for a plant-bound
  detection, storing the captured image as a plant photo via
  `/plant-instances/{key}/photos`; it SHALL NOT do so automatically, since the detection
  endpoint deliberately persists nothing.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: Q6 = "Ja, als ausdrückliche Aktion nach dem Befund"; teach-back
- **R24** — The app SHALL show detection history for a plant via
  `GET /plants/{plant_key}/history`.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #10 in-scope list; teach-back

### Quality gate

- **R25** — All user-facing strings SHALL exist as EN/DE string resources.
  - _dimension_: `non_functional` · _status_: `confirmed` · _source_: project convention; teach-back
- **R26** — `task lint` and `task test` SHALL be green, and the flow SHALL be verified
  end-to-end on the Pixel 7a against a real instance with a configured adapter.
  - _dimension_: `acceptance_criteria` · _status_: `confirmed` · _source_: project convention; teach-back

### Edge cases that must be covered

- **R27** — WHEN the backend rejects the upload with `415`, the app SHALL show a localized
  message naming the accepted formats rather than a generic failure.
  - _dimension_: `edge_cases` · _status_: `confirmed` · _source_: upload contract; teach-back
- **R28** — WHEN the microscope is detached mid-flow, the app SHALL fall back to a state
  the user can recover from, and SHALL NOT leave a capture action pointing at an absent
  device.
  - _dimension_: `edge_cases` · _status_: `assumed` · _source_: derived from the existing microscope states; not separately confirmed
- **R29** — WHEN a detection returns no findings while `is_confident` is `true`, the app
  SHALL distinguish that from the abstention state of R19.
  - _dimension_: `edge_cases` · _status_: `assumed` · _source_: derived from the response shape; not separately confirmed

## Surviving assumptions / open risks

- **The generated client will not carry the bounding-box contract.** The backend's domain
  model constrains the box to `0..1` and documents the coordinate system, but the API DTO
  `BoundingBoxSchema` declares bare floats with neither constraint nor docstring. The
  generated OpenAPI client therefore cannot express R16's semantics, and a future reader
  could plausibly misread the values as pixels. The app must assert the normalization
  itself. **Worth an upstream documentation issue**, though the operator declined to raise
  one for provenance (R14) and this was not separately put to them.
- **Correction record, 2026-08-13 — two requirements rested on a misread of the code.**
  During the R-2 decomposition, `feature-consistency-reviewer` refuted R9, and the
  refutation was verified independently against the implementation. The elicitation had
  read `MicroscopeCamera.kt` — the *interface*, whose KDoc still claims "MJPEG, captured at
  1920x1080" — and never opened `UvcMicroscopeCamera.kt`, where `grabStill()` retunes to
  `modes.largest()` and restores the preview. Consequences, all now folded in above: R9 is
  withdrawn; R8 is restated as an observation of shipped behaviour rather than work to do,
  and no longer names "4K", which is device-specific where the code deliberately says
  "largest mode offered"; R11's premise is corrected, since `:feature:settings` carries no
  still capture to move; and the `non_functional` and `constraints` cells are re-evidenced
  against the implementation instead of the interface. One question turn of the interview
  was spent on a choice the code had already made. The stale KDoc that caused it is a
  separate defect, tracked as
  [#15](https://github.com/nolte/kamerplanter-android/issues/15).
- **Whether the sensor's largest mode is fast enough in practice is still untested.** The
  reference device manages roughly 4.7 fps at 4K, and no one has measured how that feels at
  the shutter moment or whether the detail actually changes a detection outcome. This is now
  an observation about shipped behaviour, not a pending decision.
- **R28 and R29 are `assumed`**, derived from the response shape and the existing
  microscope states rather than confirmed by teach-back.
- **The consent surface carries GDPR weight.** R4 puts an Art. 6(1)(a) consent dialogue
  inside the app. The wording comes from the server, which contains the risk, but the app
  now owns the interaction where the user actually consents.
- **R11 touches a module R-1 is actively changing.** Moving CameraX out of
  `:feature:settings` while the connection work is in flight on `feat/backend-connection`
  risks a conflict; sequencing this after R-1 lands would avoid it.
- **The whole feature is blocked** until backend release `v0.2.0` is published upstream and
  R-1 delivers the generated client. Nothing here is implementable before then.
