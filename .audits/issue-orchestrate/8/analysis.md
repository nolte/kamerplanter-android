# Pre-analysis — issue #8

<!--
Run-scoped process artifact per spec/project/issue-orchestration/ §Pre-analysis artifact
lifecycle. Written and committed on feat/backend-connection, removed with a fix-forward
`git rm` on the same branch once every package is implemented and the gate is green.
It MUST NOT reach develop. The durable trail is the PR's Risk / rollout notes plus the
issue comment.

run_id: 20260812T193156Z-e8e8
-->

## Issue metadata

| Field | Value |
|---|---|
| Repository | `nolte/kamerplanter-android` |
| Issue | [#8 — Connect the app to kamerplanter via QR, username/password, or API token](https://github.com/nolte/kamerplanter-android/issues/8) |
| State | open |
| Labels | `enhancement` |
| Assignee / Milestone | none / none |
| Comments | 0 |
| Author | `nolte` (repository owner = operator → **trusted**, per `spec/claude/trusted-author-injection-guard/`) |
| Linked PRs | none open; prior art is PR #6 (merged) |
| Worktree | `~/repos/.worktrees/kamerplanter-android/connection` on `feat/backend-connection`, cut from `origin/develop` (`ea76acd`) |

## Classification

**Primary: `feature-request`.** The issue adds a new user-facing capability — interchangeable
credential paths plus persistent, editable connection management — on top of an API client
that does not exist yet.

**Secondary: `security`.** Keystore-backed secret storage, token rotation, 401 teardown and
TLS policy are all genuinely security-bearing, but the issue fixes no existing
vulnerability, so `security` does not drive routing. The spec's mandatory
classification-confirmation gate (which covers `security` and `spec-change` as *primary*
classes) therefore does not apply; the classification is recorded, not gated.

## Requirements grounding

Decomposition is grounded in [`project/requirements/backend-connection.md`](../../../project/requirements/backend-connection.md)
(44 requirements, `U_gate = 0.85 ≥ τ_high = 0.8`, saturation termination, committed as
`b969a0f`), **not** in the raw issue prose. The pre-existing
`project/requirements/app-shell-scaffold.md` was checked first and rejected as
insufficient: its bounded context explicitly excludes the real pairing endpoint, the real
OIDC handshake and real client generation.

The elicitation changed the shape of the issue in four material ways:

1. **The email + password path leaves scope.** `POST /api/v1/auth/login` returns only an
   access token and sets the refresh token as the HttpOnly cookie `kp_refresh`; there is
   no body transport on login. A natively logged-in user would hold a 15-minute session
   with no documented renewal path. Handed off upstream instead (WP-19).
2. **A fourth path enters scope.** Light-mode instances (`KAMERPLANTER_MODE=light`) have
   no accounts at all and answer `/api/v1/auth/…` with `404`; connecting to one needs a
   base URL and nothing else.
3. **Client generation entered scope** by operator decision, overriding the issue's own
   out-of-scope note — and immediately collided with schema provenance (see Risk R-1).
4. **The `/connect` deep-link contract left scope.** Upstream documentation assigns this
   app a wildcard-host `intent-filter` on `/connect`; it is credential-free instance
   discovery, functionally independent, and becomes its own issue (WP-20).

## Scope boundary

**In scope:** the vendored + pinned OpenAPI schema and the generated `core/network/`
client; the connection domain model and state machine; the QR-pairing, API-key and
light-mode paths; verification-before-persist plus tenant resolution; Keystore-backed
secret storage; the session lifecycle (body-transport refresh, rotation, 401 teardown,
disconnect); the credential-provider seam and OkHttp interceptor; the Settings surface;
TLS policy; the debug-variant `FakePairingClient`; EN/DE strings; unit tests; the quality
gate and Pixel 7a verification.

**Out of scope:** the email + password path; the `/connect` deep link; multi-account;
biometric unlock; the backend endpoints themselves; which app features consume the API
once connected.

## Specialist catalog (runtime lookup, 2026-08-12)

Globbed roots: `claude-shared/skills/*/SKILL.md` (41), `claude-shared/agents/*.md` (21),
`claude-shared/plugins/*/{skills,agents}`, `claude-android-engineering/{skills,agents}`,
the project's own `skills/` and `agents/` (**absent**), and `~/.claude/agents/*.md`
(**absent**).

**Dispatchable:** the `nolte-shared` skills and agents; `claude-android-engineering`
(`android-project-scaffold`, `android-compose-ui`, `android-barcode-scanner-scaffold`,
`android-debugging`, `android-perceived-performance`, plus the `android-ux-reviewer`
agent); `frontend-design`; and the harness built-ins `security-review` and `code-review`.

**Present on disk but NOT installed — `nolte-engineering`.** Its marketplace entry reads
*"Engineering capabilities for code-bearing projects… Code repositories adopt this on top
of nolte-shared."* This repository is exactly that, so the absence is a portfolio-hygiene
gap rather than a missing capability. Unreachable as a result:

| Specialist | What its absence costs this run |
|---|---|
| `implementation-plan-author` | The decomposition below was authored inline instead of by the planning agent `issue-orchestrate` prefers for operation 3 |
| `fullstack-developer` | Every non-UI Kotlin package falls to generalist remediation |
| `unit-test-generator` | WP-16 falls to generalist remediation |
| `code-security-reviewer` | The audit half of the security chain is unavailable; only the diff-scoped `security-review` built-in remains |
| `i18n-completeness-checker` | WP-15 has no automated completeness check |
| `quality-gate` (skill) | WP-17 runs `task check` directly instead |

The three-recurrence rule of `continuous-improvement` §Portfolio gap closure does **not**
apply here: the specialists exist and are catalogued, so the remedy is installing the
plugin, not authoring new ones.

## Work packages

Dependencies are stated as a DAG. `WP-1` is an **external** precondition in another
repository, not work in this tree.

| ID | Package | Requirements | Touches | Specialist | Depends on |
|---|---|---|---|---|---|
| WP-1 | Publish backend release `v0.2.0` upstream so its `openapi.json` asset carries the device-pairing paths | R1 | `nolte/kamerplanter` (external) | operator action — **no matching specialised agent** | — |
| WP-2 | Vendor the `v0.2.0` `openapi.json`, record tag + `sha256`, add the CI hash check | R2 | `core/network/`, `.github/workflows/`, `Taskfile.yml` | `nolte-shared:cicd-pipeline-design` (CI half); **no matching specialised agent** for the vendoring | WP-1 |
| WP-3 | Reproducible `openapi-generator` Gradle task (kotlin, `jvm-retrofit2`) emitting into `core/network/` | R3, R4 | `core/network/build.gradle.kts`, `gradle/libs.versions.toml` | **no matching specialised agent — generalist remediation** | WP-2 |
| WP-4 | Widen the pairing model into a three-kind `Connection` domain model and its state machine | R6, R29 | `feature/settings/…/Pairing*.kt` → `Connection*.kt` | **no matching specialised agent — generalist remediation** | — |
| WP-5 | Keystore-backed encrypted credential store (AES-256-GCM); non-secret parts stay in DataStore | R17, R18, R19, R25 | `feature/settings/`, `gradle/libs.versions.toml` | **no matching specialised agent — generalist remediation**; governed by `spec/android/security/`; verified by the `security-review` built-in | WP-4 |
| WP-6 | QR path: parse `{v,url,code}`, refuse unknown `v`, redeem, map `401`/`423`/`429`/expiry | R7, R8, R40–R44 | `feature/settings/QrPayloadParser.kt`, `QrScannerView.kt` | `claude-android-engineering:android-barcode-scanner-scaffold` | WP-3, WP-4 |
| WP-7 | API-key path: base URL + `kp_sk_…` form, adopt its `tenant_scope` | R9 | `feature/settings/` | `claude-android-engineering:android-compose-ui` | WP-4 |
| WP-8 | Light-mode path: detect `mode` from `/api/health`, credential-free connection, no auth header | R10, R11 | `feature/settings/`, `core/network/` | **no matching specialised agent — generalist remediation** | WP-3, WP-4 |
| WP-9 | Verify-before-persist plus tenant resolution via `GET /api/v1/tenants` | R13–R16 | `feature/settings/`, `core/network/` | **no matching specialised agent — generalist remediation**; picker UI via `android-compose-ui` | WP-3, WP-4 |
| WP-10 | Session lifecycle: body-transport refresh, rotation persistence, 401 teardown, disconnect via session delete | R21–R24 | `core/network/`, `feature/settings/` | **no matching specialised agent — generalist remediation**; verified by `security-review` | WP-5, WP-9 |
| WP-11 | Credential-provider seam + OkHttp interceptor, tenant-scoped path handling, no `core/network → feature/settings` dependency | R30–R32 | `core/network/` or new `core/auth/`, `app/` Hilt wiring | **no matching specialised agent — generalist remediation** | WP-5 |
| WP-12 | Settings surface: state display, change connection, disconnect, masked secret hint | R20, R26–R28 | `feature/settings/SettingsScreen.kt` | `claude-android-engineering:android-compose-ui`, reviewed by `claude-android-engineering:android-ux-reviewer` | WP-9 |
| WP-13 | TLS policy: no trust bypass, localized handshake-failure diagnostics | R33 | `core/network/`, string resources | **no matching specialised agent — generalist remediation**; governed by `spec/android/security/` | WP-3 |
| WP-14 | Bind `FakePairingClient` to the debug build variant only | R34 | `feature/settings/di/`, `build.gradle.kts` | **no matching specialised agent — generalist remediation** | WP-4 |
| WP-15 | EN/DE string resources for every new surface | R35 | `feature/settings/src/main/res/values*/` | `claude-android-engineering:android-compose-ui` (authoring-time) | WP-6, WP-7, WP-8, WP-12, WP-13 |
| WP-16 | Unit tests for the state machine, the parser and the storage seam | R36 | `feature/settings/src/test/`, `core/network/src/test/` | **no matching specialised agent — generalist remediation** | WP-4, WP-5, WP-6 |
| WP-17 | Two-axis version-incompatibility diagnostics (`/api/vN` major vs. SemVer) | R5 | `core/network/` | **no matching specialised agent — generalist remediation** | WP-3, WP-18 |
| WP-18 | Correct `spec/api/openapi-client-integration`: the published asset reports `info.version` `1.0.0`, not `0.1.0` | risk R-2 | `spec/api/openapi-client-integration/{en,de}.md` | `nolte-shared:spec` | — |
| WP-19 | Open the upstream issue at `nolte/kamerplanter` for body-transport refresh on login | R38 | external | **no matching specialised agent — generalist remediation** | — |
| WP-20 | Open the follow-up issue here for the `/connect` deep-link contract | R39 | external | **no matching specialised agent — generalist remediation** | — |
| WP-21 | Quality gate: `task lint` + `task test` green, Pixel 7a end-to-end verification | R37 | whole tree | `task check` directly (the `quality-gate` skill is unavailable); `claude-android-engineering:android-debugging` on a red result | all |

### Dependency ordering

```
WP-1 → WP-2 → WP-3 ─┬→ WP-6 ──┐
                    ├→ WP-8   │
                    ├→ WP-9 ──┼→ WP-10 → ┐
                    ├→ WP-13  │          │
                    └→ WP-17 ←── WP-18   │
WP-4 ─┬→ WP-5 ─┬→ WP-11 ─────────────────┤
      ├→ WP-6  └→ WP-10                  │
      ├→ WP-7                            │
      ├→ WP-8                            │
      ├→ WP-9 → WP-12 ───────────────────┤
      ├→ WP-14                           │
      └→ WP-16 ←── WP-5, WP-6            │
WP-15 ← WP-6, WP-7, WP-8, WP-12, WP-13   │
WP-18 (independent)                      │
WP-19, WP-20 (independent)               │
                                         └→ WP-21
```

Two roots run in parallel: **WP-1 → WP-2 → WP-3** (schema and client, externally blocked)
and **WP-4** (domain model, unblocked today). WP-18, WP-19 and WP-20 are fully
independent. Longest chain: WP-1 → WP-2 → WP-3 → WP-9 → WP-10 → WP-21 (six deep).

Every package states a testable acceptance criterion through the requirement IDs it
carries; none had to be recorded as a routing signal for want of one.

## Risks

- **R-1 (blocking, external).** Backend release `v0.2.0` is an unpublished draft with no
  assets. Verified: the published `v0.1.0` asset carries 14 auth paths and **zero**
  `device-pairing` paths, so the whole WP-2 → WP-3 strand — and everything downstream of
  it — cannot start until the operator publishes `v0.2.0` upstream.
- **R-2.** `spec/api/openapi-client-integration` states the on-the-wire `info.version` is
  the bare SemVer `0.1.0`; the published asset reports `1.0.0`. WP-17 must not be built
  against the spec as written. Tracked as WP-18.
- **R-3.** Issue #8 §4 proposes `EncryptedSharedPreferences` by name.
  `spec/android/security/` records Jetpack Security as **deprecated since 2025 with no
  drop-in successor** and requires Keystore-backed AES-256-GCM instead. WP-5 must not
  follow the issue's suggestion.
- **R-4 (process).** `nolte-engineering` is not installed, so 13 of 21 packages carry the
  explicit "no matching specialised agent — generalist remediation" note even though
  matching specialists exist in the portfolio. Installing the plugin would eliminate most
  of them.
- **R-5 (assumption).** Seven assumptions survive the elicitation below `confirmed`,
  listed in the requirement artifact's §Surviving assumptions: the R13 verification
  endpoint, the R8 `device_name`, light-mode tenant semantics, and R24 session-key
  discovery among them. Each is an implementation-time decision, not a blocker.

## Recorded deviations

- **Per-package dispatch gate waived.** `spec/project/issue-orchestration/` §Specialist
  dispatch requires operator confirmation at each package boundary. The operator
  authorised dispatch without per-package confirmation on 2026-08-12. Recorded here and
  carried into the PR's Risk / rollout notes.
- **Decomposition authored inline.** The spec's preferred `implementation-plan-author`
  path was unavailable (see §Specialist catalog).

## Route decision

**Decided 2026-08-12: implement directly as a single PR strand on
`feat/backend-connection`.** The operator confirmed this gate after reviewing the
decomposition, and confirmed publishing backend release `v0.2.0` upstream as the run's
first step so WP-1 clears.

**This is a recorded deviation from the boundedness rule.**
`spec/project/issue-orchestration/` §Routing defines *bounded* as one goal outcome, one PR
strand, and no new or retargeted roadmap item. This issue spans two outcomes — the API
client infrastructure and the connection capability — and would therefore normally route
to `feature-decompose` or `roadmap-plan`.

It does not, because **the formal pipeline does not exist in this repository**: `project/`
holds only `requirements/`, with no `mission.md`, `goals.md`, `roadmap.md`, `features/` or
`sprints/`. `mission-define` refuses to run without `goals.md` and an audience artefact, so
routing would first require a full planning bootstrap (`audience-identify` → goals →
`mission-define` → `roadmap-init` → `roadmap-plan`) before a single line of #8 could be
written. The operator weighed that against a single strand and chose the strand.

Splitting the issue was considered and rejected on evidence: nearly every package depends
on WP-1 rather than on the client-generation strand specifically — verification (WP-9), QR
redemption (WP-6), the refresh cycle (WP-10), light-mode detection (WP-8) and TLS
diagnostics (WP-13) all make HTTP calls, and hand-written calls were explicitly ruled out.
A split would relocate the wait, not remove it. Six of 21 packages (WP-4, WP-5, WP-14,
WP-18, WP-19, WP-20) are startable before WP-1 clears.

The two routes are **not** mixed: the entire issue is implemented directly, and nothing is
left silently unplanned.

## Dispatch log

| WP | Specialist | Result | Recorded at |
|---|---|---|---|
| — | — | not yet dispatched | — |
