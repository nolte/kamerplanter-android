# Requirements — App-shell scaffold (clickable dummy + Settings QR-pairing)

<!--
Produced via the `requirements-elicit` skill, following
spec/project/requirements-elicitation/ (canonical spec resolved from the shared
claude-shared repo; not vendored in this project).
`c_d` is an uncertainty proxy (self-consistency-derived), not a calibrated
probability. A requirement is `confirmed` only after an explicit teach-back.
-->

## Bounded context

- **What:** the first *clickable dummy* of the app shell — the final top-level
  navigation skeleton plus a **Settings** area whose centrepiece is pairing with a
  self-hosted kamerplanter backend by scanning a QR code. The GUI and the call flow
  are real; the network is **simulated behind an app-owned `PairingClient` interface**
  until the real pairing endpoint lands upstream in
  [kamerplanter#1118](https://github.com/nolte/kamerplanter/issues/1118).
- **For whom:** the Android app users (plant owners capturing pest photos); the
  counterparty is the kamerplanter backend, stood in for by a `FakePairingClient`.
- **Out of scope:** real UVC/microscope capture changes; real network-client
  generation (`spec/api/openapi-client-integration` is a separate future track); the
  real OIDC handshake and the real pairing endpoint (#1118); opening the PR.

## Understanding KPI

- Thresholds: `τ_low = 0.4`, `τ_high = 0.8`, self-consistency `k = 2`, question budget = `6` (spec defaults; unchanged).
- Question turns spent: 2 decision turns (Q1 solo; Q2–Q4 as one coupled group) + 1 teach-back = **3 / 6**.
- `U_gate = min_d c_d` over required dimensions = **0.80**
- Termination: `saturation` (`min_d c_d ≥ τ_high`; no remaining question has positive net EVPI — the only residual is the upstream #1118 wire format, explicitly out of scope and recorded as a risk).

### Gap matrix

| Dimension | Applicable | `c_d` | Uncertainty source | Evidence event |
|---|---|---|---|---|
| `functional` | yes | 0.90 | specification (resolved) | Q1 + Q2–Q4 authoritative answers; teach-back confirmed |
| `non_functional` | yes | 0.80 | interpretation (resolved) | plan invariants + teach-back (dummy-honesty, isolation, artificial delay) |
| `constraints` | yes | 0.90 | interpretation (resolved) | CLAUDE.md + ADR 0001 + Q2 (deps confined to `:feature:settings`) |
| `domain_objects` | yes | 0.85 | specification (resolved for the dummy) | Q4 answer `{baseUrl, code}` + teach-back; real wire format deferred to #1118 (risk) |
| `actors` | yes | 0.90 | specification (resolved) | bounded context + teach-back |
| `acceptance_criteria` | yes | 0.85 | specification (resolved) | A5 teach-back (click-through both paths on Pixel 7a; `task lint`/`test` green) |
| `edge_cases` | yes | 0.85 | specification (resolved) | A3 teach-back (permission denied, unparseable QR, backend failure, re-pair) |
| `scope_boundaries` | yes | 0.90 | specification (resolved) | plan out-of-scope list + teach-back |

Self-consistency (`k ≥ 2`) drove `functional`/`scope_boundaries`: three independent
readings of "final top-level destinations" (`Capture·Plants·Settings` /
`Capture·Settings` / `Capture·Plants·Gallery·Settings`) diverged → mandatory Q1
clarification below `τ_low`. The user picked `Capture·Plants·Settings`, collapsing the
divergence.

## Requirements

<!-- EARS/CNL form; each tagged confirmed/assumed with traceability. -->

### Shell / navigation

- **R1** — WHILE the app is running, the app SHALL present a Material3 bottom
  `NavigationBar` inside a `Scaffold` with exactly three top-level destinations in the
  order `Capture` → `Plants` → `Settings`, with `Capture` as the start destination.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: Q1 = "Capture · Plants · Settings"; teach-back #1
- **R2** — WHEN the user selects the `Capture` tab, the app SHALL host the existing
  `MicroscopeScreen` inside the shell `Scaffold`.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: teach-back #1
- **R3** — WHEN the user selects the `Plants` tab, the app SHALL show a placeholder
  screen with no real functionality yet.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: teach-back #1
- **R4** — WHEN the user selects the `Settings` tab, the app SHALL show the Settings
  screen whose primary action is backend pairing via QR scan.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: bounded context; teach-back #1
- **R20** — Tab labels SHALL be sourced from Android string resources with an English
  default and a German translation: `Capture`/`Aufnahme`, `Plants`/`Pflanzen`,
  `Settings`/`Einstellungen`.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: A4 teach-back

### QR scan (real)

- **R5** — WHEN the user starts pairing and the `CAMERA` permission is not yet granted,
  the app SHALL request the `CAMERA` permission before opening the camera preview.
  - _dimension_: `functional`/`edge_cases` · _status_: `confirmed` · _source_: Q2 = "Echt (CameraX + ML Kit)"; A3
- **R6** — WHEN the `CAMERA` permission is granted, the app SHALL open a CameraX preview
  and detect QR codes via ML Kit Barcode Scanning.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: Q2
- **R7** — WHEN a QR code is detected, the app SHALL parse it into a `{ baseUrl, code }`
  pairing payload.
  - _dimension_: `functional`/`domain_objects` · _status_: `confirmed` · _source_: Q4 = "Base-URL + Pairing-Code"
- **R8** — The CameraX + ML Kit dependencies and all QR-scanning code SHALL reside only
  within `:feature:settings` (mirrors the UVC isolation pattern; this is the *device*
  camera, distinct from the USB/UVC camera).
  - _dimension_: `constraints` · _status_: `confirmed` · _source_: Q2 + teach-back #2

### Pairing state machine + fake backend

- **R9** — The Settings pairing flow SHALL implement the state machine
  `idle → scanning → verifying → paired | failed`, driven by a `SettingsViewModel`.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: A1 teach-back
- **R10** — WHEN a `{ baseUrl, code }` payload is parsed, the app SHALL call the
  app-owned `PairingClient` interface, implemented in this working copy by a
  Hilt-provided `FakePairingClient` that returns a canned result after an artificial
  delay.
  - _dimension_: `functional`/`non_functional` · _status_: `confirmed` · _source_: A2 teach-back; bounded context
- **R11** — WHEN the `FakePairingClient` returns success, the app SHALL transition to
  `paired` and persist the pairing via DataStore; WHEN it returns failure, the app SHALL
  transition to `failed` and offer a retry.
  - _dimension_: `functional`/`edge_cases` · _status_: `confirmed` · _source_: Q3 = "Persistent (DataStore)"; A1
- **R12** — WHEN the app starts and a persisted pairing exists in DataStore, the app
  SHALL initialize the Settings state as `paired`.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: Q3; teach-back #3
- **R13** — WHILE in the `paired` state, the app SHALL offer an "unpair / re-pair"
  action that clears the persisted pairing and returns to `idle`.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: A1 teach-back

### Edge cases

- **R14** — WHEN the `CAMERA` permission is denied, the app SHALL show a
  permission-denied state without crashing and allow re-requesting.
  - _dimension_: `edge_cases` · _status_: `confirmed` · _source_: A3 teach-back
- **R15** — WHEN a scanned QR code cannot be parsed into `{ baseUrl, code }`, the app
  SHALL reject it as invalid and remain in `scanning` without attempting a backend call.
  - _dimension_: `edge_cases` · _status_: `confirmed` · _source_: A3 teach-back
- **R16** — A designated "fail" pairing code SHALL drive the `FakePairingClient` down the
  failure branch, so both the success and the failure UI paths are exercisable in the
  clickable dummy.
  - _dimension_: `edge_cases`/`non_functional` · _status_: `confirmed` · _source_: A2 teach-back

### Constraints / non-functional

- **R17** — The UVC engine (`libuvc`) SHALL NOT be referenced outside
  `:feature:microscope`; `:feature:settings` SHALL NOT depend on it (ADR 0001).
  - _dimension_: `constraints` · _status_: `confirmed` · _source_: CLAUDE.md / ADR 0001
- **R18** — Kotlin configuration SHALL use AGP 9 built-in Kotlin (never apply
  `org.jetbrains.kotlin.android`; options in the `android { kotlin { } }` block); all
  dependency versions SHALL be declared in `gradle/libs.versions.toml`.
  - _dimension_: `constraints` · _status_: `confirmed` · _source_: CLAUDE.md
- **R19** — The fake backend SHALL be clearly named `Fake*` and isolated behind
  `PairingClient`, so swapping in the #1118-backed client is a one-line Hilt binding
  change with no UI change.
  - _dimension_: `non_functional` · _status_: `confirmed` · _source_: A2 teach-back; plan "dummy honesty"

### Actors

- **Primary actor:** the app user (plant owner) operating the Android app on-device.
- **Counterparty actor:** the kamerplanter backend, simulated by `FakePairingClient` in
  this working copy.
  - _dimension_: `actors` · _status_: `confirmed` · _source_: bounded context; teach-back

### Acceptance criteria

- **R21** — The build SHALL pass `task lint` and `task test`, and both pairing paths
  (success and failure) SHALL be manually click-throughable on the Pixel 7a test device.
  - _dimension_: `acceptance_criteria` · _status_: `confirmed` · _source_: A5 teach-back

## Surviving assumptions / open risks

- **Upstream wire format (domain_objects residual).** The `{ baseUrl, code }` model is
  the shape the dummy parses against; the *real* QR payload and the OIDC pairing
  handshake are owned by [kamerplanter#1118](https://github.com/nolte/kamerplanter/issues/1118)
  and may differ. Mitigation: the parsed model is minimal and sits behind
  `PairingClient`, so a format change is contained to `:feature:settings` — by design
  (R19), not by luck. Not blocking.
- **DE/EN localization scope.** R20 assumes the project standardizes on Android string
  resources with an EN default + DE translation. Confirmed in teach-back (A4) for the
  three tab labels; if the app has no localization baseline yet, this requirement also
  seeds it.
- **"Fail" code convention (R16).** The exact sentinel that triggers the failure branch
  is an implementation detail to be fixed during the build; the requirement is only that
  *some* deterministic input exercises the `failed` path.
