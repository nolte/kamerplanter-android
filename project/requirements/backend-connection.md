# Requirements — Backend connection (generated API client + credential paths)

<!--
Produced via the `requirements-elicit` skill, following
spec/project/requirements-elicitation/ (canonical spec resolved from the shared
claude-shared repo; not vendored in this project).
`c_d` is an uncertainty proxy (self-consistency-derived), not a calibrated
probability. A requirement is `confirmed` only after an explicit teach-back.

Gated upstream of the `issue-orchestrate` run for
https://github.com/nolte/kamerplanter-android/issues/8
(run_id 20260812T193156Z-e8e8).
-->

## Bounded context

- **What:** the app's *real* connection to a self-hosted kamerplanter instance. Two
  halves, deliberately taken together: **(a)** the generated OpenAPI client in
  `core/network/` per [`spec/api/openapi-client-integration`](../../spec/api/openapi-client-integration/en.md),
  and **(b)** the connection feature itself — the credential paths, their verification,
  encrypted persistence, tenant resolution, Settings-based change/disconnect, and the
  credential seam that feeds authenticated calls. The operator pulled (a) into scope
  explicitly, overriding the out-of-scope note in issue #8.
- **For whom:** plant owners running their own kamerplanter instance, who connect the
  Android app to it and later change or remove that connection. The counterparty is the
  kamerplanter backend as it exists at `nolte/kamerplanter` develop after
  [#1118](https://github.com/nolte/kamerplanter/issues/1118) (PR #1129, merged
  2026-08-12).
- **Out of scope:**
  - The **email + password** connection path. `POST /api/v1/auth/login` returns only an
    access token and sets the refresh token as the HttpOnly cookie `kp_refresh`; there is
    no body transport on login, so a natively logged-in user would hold a 15-minute
    session with no documented renewal path. Tracked upstream instead (see R38).
  - The `/connect` **deep-link discovery contract** (wildcard-host `intent-filter`) that
    the upstream documentation assigns to this app. Functionally independent
    (credential-free instance discovery) and split into its own follow-up issue (R39).
  - Multi-account support (more than one instance connected at once).
  - Biometric unlock in front of the stored connection.
  - The backend endpoints themselves — owned upstream.
  - Which app features consume the API once connected; this artifact governs the
    connection, not its consumers.

Upstream context, not re-elicited here:
[`project/requirements/app-shell-scaffold.md`](app-shell-scaffold.md) (U_gate 0.80)
covers the *clickable dummy* — the shell, the QR-only pairing UI, `FakePairingClient`,
and plain-DataStore persistence. Its bounded context explicitly excludes the real pairing
endpoint, the real OIDC handshake, and real client generation, so it does **not** satisfy
the gate for this work. Its R5–R16 and R19 stay valid for the UI shell this builds on.

## Understanding KPI

- Thresholds: `τ_low = 0.4`, `τ_high = 0.8`, self-consistency `k = 2`, question budget = `6` (spec defaults; unchanged).
- Question turns spent: 3 decision turns (each a tightly-coupled group) + 1 teach-back = **4 / 6**.
- `U_gate = min_d c_d` over required dimensions = **0.85**
- Termination: `saturation` (`min_d c_d ≥ τ_high`; no remaining question carries positive net EVPI — the residuals are named as risks below, and each is resolvable by an action rather than by another question to the operator).

### Gap matrix

| Dimension | Applicable | `c_d` | Uncertainty source | Evidence event |
|---|---|---|---|---|
| `functional` | yes | 0.90 | specification (resolved) | Q1/Q2 (password path, schema pin), Q3/Q4 (light mode, deep link), Q5 (tenant); upstream `docs/en/api/authentication.md` read directly; teach-back confirmed |
| `non_functional` | yes | 0.85 | specification (resolved) | Q6 (TLS policy) + teach-back; secret-at-rest and no-plaintext-in-UI carried from issue #8 |
| `constraints` | yes | 0.90 | interpretation (resolved) | CLAUDE.md + ADR 0001 + `spec/api/openapi-client-integration` R-GEN-1…5; Q2 fixed the pin source |
| `domain_objects` | yes | 0.90 | specification (resolved) | wire formats verified against upstream docs and the v0.1.0 `openapi.json` asset, not inferred from prose |
| `actors` | yes | 0.90 | specification (resolved) | bounded context + teach-back |
| `acceptance_criteria` | yes | 0.85 | specification (resolved) | issue #8's AC list, reshaped by Q1/Q3 and confirmed in teach-back |
| `edge_cases` | yes | 0.85 | specification (resolved) | upstream error table (401/423/429), refresh-rotation semantics, light-mode 404, TLS; teach-back |
| `scope_boundaries` | yes | 0.90 | specification (resolved) | Q1/Q4 moved two items out; teach-back confirmed the remaining boundary |

Self-consistency (`k ≥ 2`) was decisive twice, and both times pushed a dimension below
`τ_low` into a mandatory question:

- **`functional` — the password path.** Three independent readings of "username +
  password" survived the evidence: capture `kp_refresh` through an OkHttp `CookieJar`;
  drop the path until the backend offers body transport on login; or store the password
  and re-login every 15 minutes. The divergence was irreducible from sources because the
  backend documents no native login renewal at all. The operator collapsed it by moving
  the path out of scope (R38).
- **`constraints` — the schema pin.** Three readings of "generate from a tagged release":
  publish v0.2.0 first; export from the develop tip with commit-SHA provenance; or
  generate from v0.1.0 and hand-write the gap. Verified evidence forced the question —
  v0.1.0's asset carries 14 auth paths and **zero** `device-pairing` paths.

## Requirements

<!-- EARS/CNL form; each tagged confirmed/assumed with traceability. -->

### API client generation and schema pinning

- **R1** — The project SHALL generate the `core/network/` API client from a vendored
  `openapi.json` sourced from the **tagged backend release `v0.2.0`**, which is published
  upstream before generation runs. That release was published on 2026-08-13; its
  `openapi.json` asset carries `sha256:7ed50815716b101f5424a45c48c6261cc6b0e57b925d2d0f491bbd578f134726`
  and does contain the `/api/v1/auth/device-pairing` paths this requirement set depends on.
  - _dimension_: `constraints` · _status_: `confirmed` · _source_: Q2 = "Upstream v0.2.0 veröffentlichen, dann daraus generieren"; teach-back
- **R2** — The vendored schema SHALL record its provenance (backend release tag) and its
  `sha256`, and CI SHALL fail the build when the document does not match the recorded
  hash.
  - _dimension_: `constraints` · _status_: `confirmed` · _source_: `spec/api/openapi-client-integration` R-GEN-2, R-GEN-3; Q2
- **R3** — Generation SHALL run through a reproducible Gradle task
  (`openapi-generator`, kotlin, `jvm-retrofit2`) whose output lands in `core/network/`,
  producing identical output on repeated runs against the same input.
  - _dimension_: `constraints` · _status_: `confirmed` · _source_: `spec/api/openapi-client-integration` R-GEN-4
- **R4** — The generated client and every networking type SHALL NOT leak outside
  `core/network/`; feature modules consume the API only through `core/network/`-owned
  seams.
  - _dimension_: `constraints` · _status_: `confirmed` · _source_: ADR 0001; `spec/api/openapi-client-integration` R-GEN-5
- **R5** — WHEN the server reports an application version or API major the client cannot
  work with, the app SHALL surface a clear localized diagnostic naming the incompatibility
  and SHALL NOT crash; the two version axes (`/api/vN` path major vs. the SemVer
  `info.version` / `/api/health.version`) SHALL be treated as independent.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: `spec/api/openapi-client-integration` Goals; teach-back

### Connection model and paths

- **R6** — The app SHALL model a connection as one of exactly three kinds — QR pairing,
  API key, or light-mode (base URL only) — replacing the dummy's single
  `{baseUrl, code}` pairing shape.
  - _dimension_: `domain_objects` · _status_: `confirmed` · _source_: Q1 (password out), Q3 (light mode in); teach-back
- **R7** — WHEN the user scans a pairing QR code, the app SHALL parse the payload
  `{"v": 1, "url": <string>, "code": <string>}`, and WHEN `v` is a version the app does
  not recognize it SHALL refuse the payload with a localized message rather than
  interpret it.
  - _dimension_: `domain_objects` · _status_: `confirmed` · _source_: upstream `docs/en/api/authentication.md` §QR payload; teach-back
- **R8** — WHEN a pairing payload is accepted, the app SHALL redeem it via
  `POST /api/v1/auth/device-pairing/redeem` with `{code, device_name}` and SHALL take the
  resulting `{access_token, token_type, expires_in, refresh_token}` from the response
  body.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: upstream §Redeeming a pairing code; teach-back
- **R9** — WHEN the user supplies an API key, the app SHALL accept a `kp_sk_…` value plus
  a base URL and SHALL send it in the same `Authorization: Bearer` header the JWT uses.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: upstream §API Keys; teach-back
- **R10** — WHEN the probed instance reports `mode` = light, the app SHALL offer a
  credential-free connection carrying the base URL **and the tenant it addresses**, and SHALL
  NOT attempt any `/api/v1/auth/…` call against it.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: Q3 = "Als vierten Pfad 'nur Base-URL' mitliefern"; upstream `main.py` `/api/health` returns `{status, version, mode}`; the tenant half verified 2026-08-16 against a running light-mode instance
  - _note_: this read "only the base URL" until 2026-08-16. A light instance serves
    `GET /api/v1/tenants` unauthenticated and answers with its system tenant, while every
    plant, diary and pest route lives under `/api/v1/t/{slug}/…` — so a connection without a
    slug can address nothing, which is exactly what it did: the plant list was empty for every
    light-mode instance. Credential-free and tenant-free are separate properties, and only the
    first one holds.
- **R11** — WHILE a light-mode connection is active, the OkHttp interceptor SHALL attach
  no `Authorization` header.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: Q3; upstream §Light Mode ("no login required")
- **R12** — The app SHALL NOT offer an email + password connection path in this scope.
  - _dimension_: `scope_boundaries` · _status_: `confirmed` · _source_: Q1 = "Aus #8 herausnehmen, Upstream-Issue öffnen"; teach-back

### Verification and tenant resolution

- **R13** — The app SHALL NOT persist any connection it has not proven: on connect it
  SHALL probe `GET /api/health` for reachability, mode and version, then make one
  authenticated call, and SHALL persist only after both succeed.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #8 §3; teach-back
- **R14** — WHEN verification fails, the app SHALL show a clear localized error and SHALL
  store nothing; the previously stored connection (if any) SHALL remain untouched.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #8 AC 2; teach-back
- **R15** — WHEN a credential-bearing connection verifies, the app SHALL resolve the
  tenant before the connection counts as established: via `GET /api/v1/tenants`, adopting
  the single tenant automatically, prompting the user to choose when there are several,
  and taking `tenant_scope` from the key itself on the API-key path.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: Q5 = "Beim Connect auflösen und mitspeichern"; teach-back
- **R16** — The app SHALL store the resolved tenant slug alongside the connection and
  SHALL display it in Settings.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: Q5; teach-back

### Storage

- **R17** — The app SHALL store every secret (refresh token, access token, API key)
  encrypted under an Android Keystore-backed key, and SHALL NOT write any secret to plain
  DataStore.
  - _dimension_: `non_functional` · _status_: `confirmed` · _source_: issue #8 §4, AC 4; teach-back
- **R18** — The app MAY keep the non-secret parts of a connection (base URL, method,
  tenant slug, user identity where known) in ordinary DataStore for display.
  - _dimension_: `non_functional` · _status_: `confirmed` · _source_: issue #8 §4; teach-back
- **R19** — The app SHALL NOT render a stored secret in clear text anywhere in the UI; a
  masked hint (for example the last four characters) is the most it SHALL show.
  - _dimension_: `non_functional` · _status_: `confirmed` · _source_: issue #8 §5, AC 4; teach-back
- **R20** — A connection SHALL survive an app restart: the user is not asked to reconnect
  after a successful connect.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #8 AC 3; teach-back

### Session lifecycle

- **R21** — WHEN the access token has expired or is about to, the app SHALL renew it via
  `POST /api/v1/auth/refresh` with the JSON body `{refresh_token}` and the header
  `Content-Type: application/json`.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: upstream §Renewing the access token (native clients); teach-back
- **R22** — WHEN a refresh succeeds, the app SHALL replace the stored refresh token with
  the rotated one from the response body, because the previous token is invalidated
  across both transports.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: upstream §Renewing… ("Rotation is cross-transport"); teach-back
- **R23** — WHEN a refresh fails, or WHEN an authenticated call returns `401`, the app
  SHALL drop to the disconnected state, clear the stored credential, and show a message
  pointing at Settings.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #8 AC 7; teach-back
- **R24** — WHEN the user disconnects, the app SHALL end the session via
  `DELETE /api/v1/users/me/sessions/{key}` where a session key is known, and SHALL
  otherwise discard the stored refresh token; it SHALL NOT call
  `POST /api/v1/auth/logout`, which rejects native clients with `403` for want of a CSRF
  cookie.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: upstream §Ending a paired device's session; teach-back
- **R25** — WHEN the user disconnects, the previous credential SHALL be removed
  completely from device storage.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #8 AC 5; teach-back

### Settings UI

- **R26** — WHILE a connection exists, Settings SHALL show its state: server URL, method,
  tenant, and the signed-in identity where known.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #8 §5; teach-back
- **R27** — The user SHALL be able to change the connection from any method to any other
  method at any time; a successful new connection SHALL replace the old one atomically.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #8 AC 5; teach-back
- **R28** — The user SHALL be able to disconnect from Settings at any time, returning the
  app to the disconnected state.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #8 §5; teach-back
- **R29** — The connection state machine SHALL widen the dummy's
  `loading → idle → scanning → verifying → paired | failed` into a method-aware shape
  covering collection per method, verification, tenant selection, connected and failed.
  - _dimension_: `domain_objects` · _status_: `confirmed` · _source_: issue #8 §1; teach-back

### Network seam

- **R30** — `core/network/` SHALL NOT depend on `feature/settings/`; the credential
  reaches it through a provider seam that `core/network/` (or a shared `core/auth/`) owns
  and the settings module implements, bound via Hilt.
  - _dimension_: `constraints` · _status_: `confirmed` · _source_: issue #8 §6, AC 6; ADR 0001 isolation pattern; teach-back
- **R31** — An OkHttp interceptor SHALL attach the stored credential to authenticated
  requests.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #8 §6; teach-back
- **R32** — The interceptor SHALL address tenant-scoped resources under
  `/api/v1/t/{tenant_slug}/…` using the stored tenant slug.
  - _dimension_: `domain_objects` · _status_: `confirmed` · _source_: upstream §URL Structure; Q5; teach-back

### Transport security

- **R33** — The app SHALL require a valid TLS certificate and SHALL NOT implement any
  trust bypass, certificate pinning-on-first-use, or custom trust manager; WHEN the TLS
  handshake fails, it SHALL show a clear localized message pointing at the server's
  certificate setup.
  - _dimension_: `non_functional` · _status_: `confirmed` · _source_: Q6 = "Gültiges Zertifikat als harte Voraussetzung"; teach-back

### Development affordance

- **R34** — `FakePairingClient` SHALL remain available behind a debug build variant so the
  flow can be clicked through without a reachable backend, and the release build SHALL NOT
  be able to bind it.
  - _dimension_: `constraints` · _status_: `confirmed` · _source_: Q7 = "Hinter Debug-Flag behalten"; teach-back

### Quality gate

- **R35** — All user-facing strings SHALL exist as EN/DE string resources, consistent with
  the existing shell.
  - _dimension_: `non_functional` · _status_: `confirmed` · _source_: issue #8 AC 8; teach-back
- **R36** — Unit tests SHALL cover the connection state machine, the QR payload parser,
  and the storage seam.
  - _dimension_: `acceptance_criteria` · _status_: `confirmed` · _source_: issue #8 AC 9; teach-back
- **R37** — `task lint` and `task test` SHALL be green, and the flow SHALL be verified
  end-to-end on the Pixel 7a against a real kamerplanter instance.
  - _dimension_: `acceptance_criteria` · _status_: `confirmed` · _source_: issue #8 AC 9–10; teach-back

### Hand-offs out of scope

- **R38** — An upstream issue SHALL be opened at `nolte/kamerplanter` requesting body
  transport of the refresh token on `POST /api/v1/auth/login`, mirroring
  `device-pairing/redeem`, so the email + password path becomes implementable natively.
  - _dimension_: `scope_boundaries` · _status_: `confirmed` · _source_: Q1; teach-back
- **R39** — A follow-up issue SHALL be opened in this repository for the `/connect`
  deep-link contract: the wildcard-host `intent-filter`, `/connect` handling, and
  pre-filling the base URL from the link.
  - _dimension_: `scope_boundaries` · _status_: `confirmed` · _source_: Q4 = "Separates Issue nach #8"; teach-back

### Edge cases that must be covered

- **R40** — WHEN redemption returns `401`, the app SHALL show one message covering
  unknown, already-redeemed and expired codes alike, and SHALL NOT try to distinguish
  them — the backend deliberately offers no oracle.
  - _dimension_: `edge_cases` · _status_: `confirmed` · _source_: upstream §Error responses; teach-back
- **R41** — WHEN redemption returns `423 Locked`, the app SHALL surface the remaining
  lockout duration the response states, and SHALL make clear the same QR code stays
  redeemable after the lockout while its own validity lasts.
  - _dimension_: `edge_cases` · _status_: `confirmed` · _source_: upstream §Error responses; teach-back
- **R42** — WHEN redemption returns `429`, the app SHALL show a distinct rate-limit
  message rather than a generic failure.
  - _dimension_: `edge_cases` · _status_: `confirmed` · _source_: upstream §Error responses; teach-back
- **R43** — The app SHALL account for the pairing code's 60–120 second validity: a code
  that has expired between scan and redemption SHALL produce the R40 message and return
  the user to the scanner rather than to a dead end.
  - _dimension_: `edge_cases` · _status_: `confirmed` · _source_: upstream §Security notes; teach-back
- **R44** — The existing camera edge cases from the dummy — permission denied,
  camera unavailable, unparseable QR — SHALL keep behaving as they do today.
  - _dimension_: `edge_cases` · _status_: `confirmed` · _source_: `app-shell-scaffold.md` A3; teach-back

## Surviving assumptions / open risks

- **~~Backend release `v0.2.0` is not published yet~~ Resolved 2026-08-13.** The entry
  recorded a hard, external precondition for the client-generation half of the work: as of
  2026-08-12 the release was a draft with no assets, so R1/R2 could not be satisfied. It was
  published on 2026-08-13 with an `openapi.json` asset
  (`sha256:7ed50815716b101f5424a45c48c6261cc6b0e57b925d2d0f491bbd578f134726`) that carries the
  `/api/v1/auth/device-pairing` and `/api/v1/auth/device-pairing/redeem` paths whose absence
  from `v0.1.0` forced the pin decision in the first place. No longer schedule-blocking.
- **`spec/api/openapi-client-integration` carries a factual drift.** It states the
  on-the-wire `info.version` is the bare SemVer `0.1.0`; the published v0.1.0 asset
  actually reports `1.0.0`. Verified directly against the release asset — and the `v0.2.0`
  asset reports `1.0.0` as well, so `info.version` does not track the release tag at all and
  the divergence is systematic rather than a slip in one release. The spec needs correcting
  before R5's two-axis handling is implemented against it. `assumed`: that the spec is wrong
  rather than the asset.
- **~~The encryption mechanism for R17 is an open implementation choice.~~ Resolved
  2026-08-13.** The constraint this entry recorded still holds: `spec/android/security/`
  requires Keystore-backed AES-256-GCM and rules out **Jetpack Security /
  `EncryptedSharedPreferences`, deprecated as of 2025 with no drop-in successor**, so the
  mechanism issue #8 §4 proposes by name must not be used. What is no longer open is the
  choice it left to the implementer: `KeystoreSecretCipher` implements `AES/GCM/NoPadding`
  with a 256-bit key generated in `AndroidKeyStore` under
  `setRandomizedEncryptionRequired(true)`, and **added nothing to the version catalog** —
  Keystore and `javax.crypto` are platform API, so the spec's Tink candidate would have
  bought a dependency and no security. Struck through rather than deleted, because the
  ruled-out option is the half worth remembering.
- **The verification call of R13 is not pinned to a specific endpoint.** `GET
  /api/v1/tenants` is the natural candidate since R15 needs it anyway, but this is
  `assumed`, not confirmed — and it does not apply on the light-mode path, where
  `GET /api/health` alone is the whole check.
- **The `device_name` sent on redemption (R8) is unspecified.** The field is optional and
  capped at 64 characters; using the device model is `assumed`.
- ~~**Light-mode tenant semantics are unverified.**~~ **Resolved 2026-08-16** against a
  running light-mode instance. It exposes the tenant-scoped routes like any other: `GET
  /api/v1/tenants` answers unauthenticated with its system tenant, and every plant route is
  `/api/v1/t/{slug}/…`. The assumption recorded here — "light mode is effectively
  single-tenant and R15 does not apply there" — was half right and wholly harmful: the
  instance does ship a single tenant, but the app read that as *no* tenant, addressed nothing,
  and showed an empty plant list. R15's adoption rule now applies to every method, and R10
  carries the slug.
- **Session-key discovery for R24 is unverified.** Ending a paired session needs the
  session's `key`; `GET /api/v1/users/me/sessions` is the plausible source, but the
  mapping from "this device's connection" to "this session key" was not confirmed.
  `assumed`: the app can identify its own session, and falls back to discarding the token
  when it cannot.
