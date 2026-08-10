# CLAUDE.md

Native Android companion app for [nolte/kamerplanter](https://github.com/nolte/kamerplanter).
Driving requirement: capture pest photos from a USB (UVC) microscope and upload them into
kamerplanter's identification pipeline —
[issue #1](https://github.com/nolte/kamerplanter-android/issues/1). The stack decision and
its rationale live in [ADR 0001](docs/en/adrs/0001-tech-stack.md); read it before
proposing stack changes.

## Architecture

- Kotlin + Jetpack Compose, Gradle 9 / AGP 9 with **built-in Kotlin** — never apply
  `org.jetbrains.kotlin.android`; AGP 9 refuses it. Kotlin compiler options live in the
  `android { kotlin { } }` block.
- Multi-module: `app/` (entry point, navigation, Hilt aggregation), `core/network/`
  (kamerplanter API; the OpenAPI-generated client lands here), `feature/microscope/`
  (UVC capture).
- **UVC isolation rule (ADR 0001):** the UVC engine (AndroidUSBCamera/AUSBC, catalog key
  `ausbc`) may only be referenced inside `feature/microscope/`, always behind the
  app-owned `MicroscopeCamera` interface. Never let it leak into other modules.
- Dependency versions live in `gradle/libs.versions.toml` (version catalog); Renovate
  keeps them current.

## Commands

- `task setup` — git hooks + docs virtualenv (one-time)
- `task lint` / `task test` / `task docs` / `task check` — the CI-parity gate
- `task assemble` — release APK
- Local builds need JDK 21 (with `javac`) and an Android SDK pointed to by
  `local.properties` (`sdk.dir=…`); CI's ubuntu runners ship both.

## Documented layout deviations

Two deliberate deviations from `spec/project/project-structure/` (Android/Gradle
convention wins; do not "fix" them):

- Source lives in Gradle modules at the repo root (`app/`, `core/`, `feature/`), not
  under `src/<component>/`.
- Unit tests live inside each module (`<module>/src/test/`), not in the root `tests/`
  directory; root `tests/` is reserved for future cross-module E2E suites.

## Conventions

- Branching: portfolio git-flow — work lands on `develop` via PRs, `main` is the release
  presentation branch (refreshed by `release-cd-refresh-master.yml`).
- Repo settings are code: `.github/settings.yml` (Probot Settings App). Never change
  settings through the GitHub UI.
- Commit messages follow Conventional Commits; PR flow per the portfolio
  `pull-request-workflow` spec.
