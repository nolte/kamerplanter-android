# OpenAPI-Based Backend API Integration

Status: draft
Portfolio-Scope: local

## Context
<!-- Why does this spec exist? What problem, user need, or constraint drives it? -->

kamerplanter-android is a full mobile client for the self-hosted
[nolte/kamerplanter](https://github.com/nolte/kamerplanter) backend (FastAPI). Per
[ADR 0001](../../../docs/en/adrs/0001-tech-stack.md), the API client lives in
`core/network/`, is **generated** from the backend's OpenAPI schema via
`openapi-generator` (kotlin, `jvm-retrofit2`), and runs on Retrofit + OkHttp +
kotlinx.serialization. There are no hand-maintained DTOs.

The backend establishes the concrete surface this spec integrates against:

- **Path-based major versioning:** routes live under `/api/v1/…`; the OpenAPI document
  is served at `/api/v1/openapi.json`.
- **Two independent version axes:** the URL major (`/api/vN`) is *not* the backend
  application version. The application version is a SemVer string (`settings.app_version`,
  surfaced as OpenAPI `info.version`). At the time of writing the backend release tag is
  `v0.1.0`, while the on-the-wire `info.version` / `/api/health.version` is the bare SemVer
  `0.1.0`; the API path is already `/api/v1` — the two axes must never be conflated.
- **Health endpoint:** `GET /api/health` (root-level, documented "for M2M consumers")
  returns `{ "status": "healthy", "version": <app_version> }`.
- **Multi-tenancy:** tenant-scoped routes take the form `/api/v1/t/{tenant_slug}/…`.
- **Schema distribution:** the backend publishes `openapi.json` as a **GitHub release
  asset** on tagged releases (e.g. release `v0.1.0`), and GitHub records its `sha256`.
  The backend keeps the working-copy `openapi.json` gitignored (a build artifact produced
  by `task openapi:export`); the release asset is the durable, addressable copy.

Because the backend is **self-hosted**, the app and the server drift in *both*
directions: a server may be newer than the installed app (returns fields/endpoints the
generated client does not know), or older (the app expects things an older server does not
offer). Basic backward compatibility across this drift is the driving requirement of this
spec.

## Goals
<!-- What this spec aims to achieve. Bullet points, outcome-oriented. -->
- Reproducible, deterministic client generation from a **pinned, vendored** OpenAPI
  document sourced from a tagged backend release.
- A clearly separated two-axis version model: API major (`/api/vN` path) vs. backend
  application version (SemVer, `info.version` / `/api/health.version`).
- Tolerant-reader deserialization so an unexpectedly newer server never crashes the app.
- Runtime negotiation of the API major version: use the highest major both client and
  server support.
- Graceful degradation plus clear, localized diagnostics when the server version is
  incompatible or unreachable — never a hard crash.
- Keep the ADR 0001 isolation rules intact: networking stays inside `core/network/`; UVC
  never leaks in.

## Non-Goals
<!-- Explicitly out of scope. Prevents creep. -->
- Hand-maintained DTOs or a hand-written HTTP client.
- Offline caching / sync semantics (Room) — a separate concern if it ever becomes a
  feature.
- The full OIDC authentication flow design — its own spec; covered here only where version
  compatibility touches it.
- Server-side API design, deprecation policy, or the shape of a future `/api/v2` — those
  belong to the backend repository.
- Defining the exact endpoint-to-feature mapping of the app — this spec governs *how* the
  client binds to the API, not *which* features consume it.

## Requirements
<!-- Use RFC 2119 keywords: MUST, SHOULD, MAY. One atomic requirement per bullet. -->

### Client generation & schema pinning
- **R-GEN-1 — MUST** generate the API client from a versioned, checked-in ("vendored")
  OpenAPI document, never by fetching a live endpoint at build time.
- **R-GEN-2 — MUST** source the vendored document from a **tagged backend GitHub release
  asset** (`openapi.json`), recording provenance: the backend release tag and the release's
  `sha256`.
- **R-GEN-3 — MUST** verify the vendored document against its recorded `sha256` in CI, so a
  corrupted or silently swapped schema fails the build.
- **R-GEN-4 — MUST** perform generation via a reproducible Gradle task
  (`openapi-generator`, kotlin, `jvm-retrofit2`) whose output lands in `core/network/`;
  running it twice on the same input MUST produce identical output.
- **R-GEN-5 — MUST NOT** let the generated client, or any networking type, leak outside
  `core/network/`; feature modules consume the API only through `core/network/`-owned
  interfaces (ADR 0001 isolation, mirroring the UVC rule).
- **R-GEN-6 — SHOULD** treat every schema update as a single reviewable commit that bumps
  the vendored document, its provenance tag, and its `sha256` together, so the DTO diff is
  visible in review.
- **R-GEN-7 — SHOULD** fail CI when the checked-in generated client no longer matches the
  pinned schema (regenerate + diff must be empty), preventing schema/code drift.

### Two-axis version model
- **R-VER-1 — MUST** model the API major version (URL segment `/api/vN`) and the backend
  application version (SemVer from `info.version` / `/api/health.version`) as two distinct
  axes; the client MUST NOT derive one from the other.
- **R-VER-2 — MUST** declare, in client-owned configuration, the ordered set of API majors
  the client supports and a minimum supported backend application version
  (`MIN_SUPPORTED`). The initial `MIN_SUPPORTED` floor is `0.1.0` (SemVer, matching the
  backend's current `v0.1.0` release line) and tracks forward with the backend.

### Backward compatibility (tolerant reader)
- **R-COMPAT-1 — MUST** deserialize JSON with unknown fields ignored
  (`ignoreUnknownKeys`) and input values coerced to declared defaults
  (`coerceInputValues`) — already configured in `core/network` `NetworkModule.provideJson`;
  this spec makes it binding.
- **R-COMPAT-2 — MUST** model newly added response fields as optional/nullable with
  defaults, so an older server that omits them deserializes without error.
- **R-COMPAT-3 — MUST NOT** assume the presence of a field or endpoint introduced by a
  newer server version without first establishing its availability (feature detection over
  assumption).
- **R-COMPAT-4 — SHOULD** treat a missing optional endpoint on an older server
  (`404`/`501`) as "feature unavailable", not as a hard error.

### Runtime major-version negotiation
- **R-NEG-1 — MUST**, on connecting to a server, determine which API majors the server
  offers and select the highest major supported by both client and server (highest common
  major).
- **R-NEG-2 — MUST** use the negotiated major as the path prefix (`/api/vN/…`, including
  tenant-scoped `/api/vN/t/{tenant_slug}/…`) for every versioned request in that session;
  the root-level, version-independent `/api/health` is excluded from the prefix.
- **R-NEG-3 — SHOULD** discover server majors by probing candidate majors from the highest
  client-known major downward (e.g. `GET`/`HEAD` `/api/v{n}/openapi.json`) until one
  responds, since the backend currently exposes no dedicated "supported majors" index
  (see Open Questions).
- **R-NEG-4 — SHOULD** cache the negotiation result per server base URL and re-evaluate it
  on connection failure or on an explicit user action.
- **R-NEG-5 — MAY** let the user force re-discovery from settings.

### Health gate & graceful degradation
- **R-HEALTH-1 — MUST** query `GET /api/health` before exercising features, reading
  `status` and `version`.
- **R-HEALTH-2 — MUST** compare `version` against `MIN_SUPPORTED` by SemVer precedence
  (never lexical string comparison), normalizing an optional leading `v`; when
  `version` < `MIN_SUPPORTED`, show a visible, localized warning and continue in a reduced
  mode (only features compatible with that server version) rather than hard-failing.
- **R-HEALTH-3 — MUST** surface a clear, localized error (no crash) when the server is
  unreachable or reports a non-healthy `status`.
- **R-HEALTH-4 — SHOULD** disable, in reduced mode, precisely the features that need the
  missing server version or major, rather than blocking the app globally.

## Acceptance Criteria
<!-- Testable, checkable conditions. A reviewer should be able to mark each as done/not done. -->
- [ ] A Gradle task generates the client deterministically from the vendored
      `openapi.json`; running it twice yields byte-identical output. (R-GEN-1, R-GEN-4)
- [ ] The vendored schema carries provenance (backend release tag) and a `sha256` that CI
      verifies; a tampered schema fails the build. (R-GEN-2, R-GEN-3)
- [ ] A CI check fails when the checked-in generated client does not match the pinned
      schema. (R-GEN-7)
- [ ] `core/network/` exposes no UVC/`ausbc` symbols, and feature modules reference the API
      only through `core/network/` interfaces. (R-GEN-5)
- [ ] Deserializing a response with extra unknown fields (newer server) does not throw and
      returns the known fields correctly. (R-COMPAT-1)
- [ ] Deserializing a response missing newly added optional fields (older server) yields
      defaults/nulls without error. (R-COMPAT-2)
- [ ] Against a server offering only `/api/v1`, the client selects v1 even though it also
      knows v2; against a server offering v1 and v2, it selects v2. (R-NEG-1, R-NEG-3)
- [ ] All session requests use the negotiated major as their path prefix, including
      tenant-scoped routes. (R-NEG-2)
- [ ] When `/api/health.version` < `MIN_SUPPORTED`, a localized warning appears and the app
      stays usable in reduced mode (no hard fail, no crash). (R-HEALTH-2, R-HEALTH-4)
- [ ] When the server is unreachable, a localized error appears instead of a crash.
      (R-HEALTH-3)
- [ ] The client queries `/api/health` and reads `status` + `version` before exercising
      any feature. (R-HEALTH-1)
- [ ] The client declares an ordered set of supported API majors and a `MIN_SUPPORTED`
      floor, and derives neither version axis from the other. (R-VER-1, R-VER-2)
- [ ] The `version` vs. `MIN_SUPPORTED` gate uses SemVer precedence with optional-`v`
      normalization: `0.10.0` ranks above `0.9.0`, not below. (R-HEALTH-2)
- [ ] A field or endpoint introduced by a newer server is used only after its availability
      is established (feature detection), exercised by a test. (R-COMPAT-3)

## Open Questions
<!-- Unresolved decisions, known unknowns, things that need a stakeholder answer. -->
- **Major discovery mechanism:** the backend exposes no explicit "supported majors" index
  today; probing `/api/v{n}/openapi.json` is a workaround. Should the backend gain a
  discovery/capabilities endpoint (or add a `supported_majors` field to `/api/health`)?
  Tracked as [nolte/kamerplanter#1124](https://github.com/nolte/kamerplanter/issues/1124).
- **Provenance encoding:** how is the release tag + `sha256` pinned technically — a sibling
  provenance file, a header comment, or a Gradle property consumed by the verify task?
- **Is `/api/health` sufficient for the gate?** It returns `status` + app `version` but not
  the set of API majors; negotiation currently relies on probing. Decide whether the health
  payload should be extended (couples to
  [nolte/kamerplanter#1124](https://github.com/nolte/kamerplanter/issues/1124)).
