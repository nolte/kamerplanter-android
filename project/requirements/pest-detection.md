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
- Question turns spent: 3 decision turns (each a tightly-coupled group) + 1 teach-back = **4 / 6**.
- `U_gate = min_d c_d` over required dimensions = **0.85**
- Termination: `saturation` (`min_d c_d ≥ τ_high`; no remaining question carries positive net EVPI — the residuals below are resolvable by action or by an upstream decision, not by another question to the operator).

### Gap matrix

| Dimension | Applicable | `c_d` | Uncertainty source | Evidence event |
|---|---|---|---|---|
| `functional` | yes | 0.90 | specification (resolved) | Q1–Q6 across three decision turns; the issue's own verified endpoint table; teach-back confirmed |
| `non_functional` | yes | 0.85 | specification (resolved) | Q1 (4K capture and its latency cost) + the 8 MB / MIME upload contract; teach-back |
| `constraints` | yes | 0.90 | interpretation (resolved) | CLAUDE.md + ADR 0001 isolation rule + Q2 (shared camera module); `MicroscopeCamera` read directly |
| `domain_objects` | yes | 0.90 | specification (resolved) | response shape and bounding-box semantics verified against the backend domain model, not inferred from prose |
| `actors` | yes | 0.90 | specification (resolved) | bounded context + teach-back; the self-hoster's configuration is what gates availability |
| `acceptance_criteria` | yes | 0.85 | specification (resolved) | derived from the issue's in-scope list, narrowed by Q1–Q6; teach-back |
| `edge_cases` | yes | 0.85 | specification (resolved) | upstream status/consent gating, abstention, MIME and size limits, mid-flow device detach; teach-back |
| `scope_boundaries` | yes | 0.90 | specification (resolved) | the issue's explicit out-of-scope list, extended by Q6's provenance decision; teach-back |

Self-consistency (`k ≥ 2`) was decisive on two dimensions:

- **`non_functional` — microscope capture resolution.** Two readings survived the sources:
  one 1080p frame from the running stream is enough (the stream is already fixed at
  1920×1080 and `captureFrame()` grabs from it), versus reconfiguring to 4K for the shutter
  moment. The divergence was irreducible from evidence because no measurement exists of
  whether 1080p resolves a spider mite well enough for the direct-detection mode. The
  operator chose 4K, accepting the latency.
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
- **R8** — WHEN the user captures from the microscope, the app SHALL reconfigure the stream
  to 4K for the capture and return it to the 1080p preview afterwards, accepting the
  latency that the reference device's ~4.7 fps at 4K implies.
  - _dimension_: `non_functional` · _status_: `confirmed` · _source_: Q1 = "Für die Aufnahme auf 4K umschalten"; teach-back
- **R9** — The `MicroscopeCamera` seam SHALL be extended to express a capture resolution;
  it exposes none today, and `captureFrame()` currently grabs from a stream fixed at
  1920×1080.
  - _dimension_: `constraints` · _status_: `confirmed` · _source_: `MicroscopeCamera.kt` read directly; teach-back
- **R10** — The app SHALL ensure every uploaded image stays within the backend's 8 MB
  limit and is `image/jpeg` or `image/png`, re-encoding or downscaling where the captured
  frame would exceed it — which the 4K microscope frame may.
  - _dimension_: `non_functional` · _status_: `confirmed` · _source_: issue #10 upload contract; teach-back
- **R11** — The shared camera capability (CameraX still capture, plus the existing QR
  scanning) SHALL move out of `:feature:settings` into a module both consumers share.
  - _dimension_: `constraints` · _status_: `confirmed` · _source_: Q2 = "In ein gemeinsames Modul ziehen"; teach-back
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
- **R8's 4K decision rests on no measurement.** Whether 1080p would in fact have sufficed
  for direct detection was never tested; the operator chose detail over latency on
  reasoning, not evidence. If 4K capture proves too slow in practice, this is the first
  requirement to revisit — it is a preference, not a constraint.
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
