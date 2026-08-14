# kamerplanter-android

Native Android companion app for [kamerplanter](https://github.com/nolte/kamerplanter) — capture pest photos with a USB (UVC) microscope and feed them into the plant-care pipeline.

[![CI](https://github.com/nolte/kamerplanter-android/actions/workflows/ci.yml/badge.svg?branch=develop)](https://github.com/nolte/kamerplanter-android/actions/workflows/ci.yml)

## Purpose

Phone-camera macro shots are rarely sharp enough to tell spider mites, thrips, aphids or scale apart. This app connects a cheap USB (UVC) microscope over USB-C/OTG, shows a live preview, grabs a still frame as JPEG, and hands it to kamerplanter's upload / pest-identification flow — without touching the phone's WiFi connection. External UVC cameras are not reachable from web or cross-platform camera APIs, which is why this app is native Kotlin ([ADR 0001](docs/en/adrs/0001-tech-stack.md), [issue #1](https://github.com/nolte/kamerplanter-android/issues/1)).

## Usage

```bash
task setup    # one-time: git hooks + docs virtualenv
task lint     # detekt (incl. ktlint rules) + Android Lint
task test     # JVM unit tests
task check    # aggregated quality gate
task assemble # release APK
```

Building requires a JDK 21 and the Android SDK (`local.properties` → `sdk.dir`). Releases ship as signed APKs via GitHub Releases.

## Structure

| Path | Contents |
| --- | --- |
| `app/` | Application module — entry point, navigation, theming |
| `core/connection/` | Which instance the app talks to and with which credential, plus the two stores that persist it |
| `core/network/` | kamerplanter API client (OpenAPI-generated, Retrofit) |
| `feature/microscope/` | UVC capture feature; the engine stays isolated behind `MicroscopeCamera` |
| `feature/settings/` | Connecting to an instance — QR pairing and the connection state machine |
| `docs/` | MkDocs documentation, incl. architecture decision records |
| `spec/` | Requirements and domain knowledge |

The Gradle multi-module layout (`app/`, `core/`, `feature/` at the root) is the documented Android-conventional deviation from the portfolio's `src/<component>/` rule — see `CLAUDE.md`.

## Related repositories

- [nolte/kamerplanter](https://github.com/nolte/kamerplanter) — the plant lifecycle management system this app talks to
- [nolte/kamerplanter-ha](https://github.com/nolte/kamerplanter-ha) — Home Assistant integration
- [nolte/gh-plumbing](https://github.com/nolte/gh-plumbing) — shared CI/CD workflows and repo configuration

## Status

Early scaffold. The tech stack is decided ([ADR 0001](docs/en/adrs/0001-tech-stack.md)); the USB microscope capture flow (issue #1) is the first feature in progress.

## License

[MIT](LICENSE)
