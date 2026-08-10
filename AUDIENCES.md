# Audiences — kamerplanter-android

<!--
Produced via the `audience-identify` skill, following
spec/project/audience-identification/.
Do not add audiences without first declaring the bounded context below.
-->

## Bounded context

`nolte/kamerplanter-android` is the native Android companion app of the
kamerplanter system. The context covers the app itself: USB (UVC) microscope
capture (issue #1), upload into the kamerplanter pest-identification pipeline,
and — per the full-mobile-client scope decision in
[ADR 0001](docs/en/adrs/0001-tech-stack.md) — future plant-management and
reminder features on the phone.

Explicitly outside: the kamerplanter backend and web frontend
([nolte/kamerplanter](https://github.com/nolte/kamerplanter)), the Home
Assistant integration, the microscope hardware/firmware, iOS, and the operation
of the kamerplanter server instance itself.

## Audiences

Portfolio-baseline track defaults: `user` → `user-docs`; `contributor` /
`operator` / `release-manager` → `developer-docs`. Overrides carry an inline
rationale.

### Direct consumers

- **Plant owner / home grower** — _category_: direct-consumer · _surface_: app
  UI on their own device · _expects_: reliable microscope detection, live
  preview, one-tap capture and upload into the identification flow; later the
  full plant-care surface on the go · _track_: `user-docs` ·
  _status_: `confirmed` (the maintainer is one, in person) ·
  _criticality_: primary
  - Open questions: none
- **Community-garden administrator** — _category_: direct-consumer ·
  _surface_: app UI · _expects_: the same capture/upload flow usable in a
  shared-garden setting · _track_: `user-docs` · _status_: `assumed` ·
  _criticality_: peripheral
  - Open questions: does this group need account switching / multi-tenant
    support in the app, mirroring kamerplanter's multi-tenancy?

### Operators

- **Self-hoster (kamerplanter instance operator)** — _category_: operator ·
  _surface_: kamerplanter server configuration plus the app's server-URL/auth
  settings · _expects_: the app tracks the published API of their instance
  version and documents its minimum backend version · _track_: `developer-docs`
  · _status_: `confirmed` (the maintainer self-hosts the instance the app is
  built against) · _criticality_: secondary
  - Open questions: none
- **Sideload installer** — _category_: operator · _surface_: GitHub Releases
  APK, Obtainium-style updaters · _expects_: signed APKs, release notes, a
  working update path without Play Store · _track_: `user-docs` (override of
  the operator baseline: installation guidance addresses end users installing
  the app, not developers) · _status_: `assumed` · _criticality_: secondary
  - Open questions: none

### Contributors / maintainers

- **Maintainer & Android contributors** — _category_: contributor ·
  _surface_: Kotlin/Gradle codebase, PRs, `CLAUDE.md`, ADRs, Taskfile ·
  _expects_: green CI, documented conventions and layout deviations, working
  `task` targets · _track_: `developer-docs` · _status_: `confirmed`
  (maintainer) · _criticality_: secondary
  - Open questions: whether external contributors materialize (repo is new).
- **Release manager** — _category_: contributor (portfolio base audience
  `release-manager`) · _surface_: release-drafter/publish workflows, APK
  signing, gh-plumbing pins · _expects_: reproducible tag-pinned release
  pipeline, curatable draft notes · _track_: `developer-docs` ·
  _status_: `confirmed` (maintainer in personal union) ·
  _criticality_: secondary
  - Open questions: none
- **AI agents (Claude Code sessions)** — _category_: contributor ·
  _surface_: `CLAUDE.md`, `.claude/`, spec corpus, Taskfile targets ·
  _expects_: accurate architecture notes, explicit documented deviations,
  deterministic commands · _track_: `developer-docs` · _status_: `assumed` ·
  _criticality_: secondary
  - Open questions: none

### Governing parties

- **Portfolio spec corpus & audits** — _category_: governing · _surface_: the
  nolte spec suite (project-structure, branching-model, quality-gate, …) and
  its audit skills · _expects_: the repository conforms to portfolio
  conventions; deviations are documented, not silent · _track_:
  `developer-docs` · _status_: `assumed` · _criticality_: peripheral
  - Open questions: Google Play review becomes a governing party if the app is
    ever published to the Play Store (currently GitHub-Releases-only).

### Indirect audiences

- **kamerplanter backend maintainer** — _category_: indirect · _surface_: the
  OpenAPI contract and cross-repo issues (affected by the app's API needs
  without using the app) · _expects_: the app consumes the published schema;
  new upload/identification requirements arrive as backend issues, not
  surprises · _track_: `developer-docs` · _status_: `assumed` ·
  _criticality_: peripheral
  - Open questions: none
- **Household members** — _category_: indirect · _surface_: none (affected via
  healthier plants, never interact with the app) · _expects_: nothing from the
  context directly · _track_: `user-docs` (nearest fit per baseline; no actual
  docs consumption expected) · _status_: `assumed` · _criticality_: peripheral
  - Open questions: none

## Open questions (cross-cutting)

- Play Store publication would add a governing party (store review) and change
  the sideload-installer entry — revisit on that decision.

## Revisit triggers

- Expansion beyond the microscope feature into the full mobile client
  (new public surface per feature).
- Play Store (or F-Droid) publication.
- New consumed backend API surface beyond upload/identification.
- Any collection of regulated data classes (e.g. photo EXIF/location, user
  accounts) — would add privacy-governance audiences.
- An iOS decision (out of scope today).
