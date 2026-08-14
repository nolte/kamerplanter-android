---
title: ADR 0001 — Tech stack
audience: [contributor]
content_mode: explanation
track: developer-docs
last_updated: 2026-08-10
source_language: en
---

# ADR 0001 — Tech stack for kamerplanter-android

- Status: accepted
- Date: 2026-08-10
- Deciders: nolte
- Driven by: [Issue #1 — Capture pest photos via USB (UVC) microscope](https://github.com/nolte/kamerplanter-android/issues/1)

## Context

kamerplanter-android is the native Android companion to
[nolte/kamerplanter](https://github.com/nolte/kamerplanter) (FastAPI backend + React
frontend). The reason a native app exists at all is issue #1: capturing pest photos from a
USB (UVC) microscope (reference device `1b3f:2002`, MJPEG, 1080p preview sweet spot;
stills are taken at a larger mode where the device offers one) and feeding
them into kamerplanter's upload / pest-identification pipeline.

That issue establishes a hard technical constraint that dominates the stack choice:

- `getUserMedia` / WebView / PWA do not expose external UVC cameras on Android.
- WebUSB cannot drive UVC cameras (no isochronous endpoint support).
- `react-native-vision-camera` (Camera2-based) does not reliably see external UVC devices.
- Android's own Camera2/CameraX external-camera support is OEM-dependent and cannot be
  relied on for arbitrary devices.

The USB frame grab therefore requires native Android code wrapping a libuvc-based library,
regardless of the UI framework.

Scope decision (2026-08-10): the app is a **full mobile client** for kamerplanter — the
microscope capture is feature #1, with plant management, reminders, etc. expected to
follow. The architecture must be modular from the start.

## Considered options

1. **Native Kotlin + Jetpack Compose** — integrate a libuvc-based library directly.
2. **React Native + native UVC module** — React UI, UVC via community bridge modules
   (`@and2long/react-native-uvc-camera`); requires a development build, carries both the
   JS and the Gradle/NDK toolchain.
3. **Flutter + platform channel** — same bridge problem as React Native, no reuse of the
   existing React knowledge.

## Decision

**Option 1: native Kotlin + Jetpack Compose.** The defining feature is native either way;
a bridge framework would add a second toolchain and a dependency on thinly maintained
wrapper packages without removing any native work. React reuse from the main repo does not
pay for that cost.

### Stack

| Concern | Choice | Notes |
| --- | --- | --- |
| Language | Kotlin (current stable 2.x) | JDK 17 bytecode target |
| Build | Gradle 9, Kotlin DSL, version catalog (`gradle/libs.versions.toml`) | AGP 9 with built-in Kotlin (no separate `org.jetbrains.kotlin.android` plugin) |
| UI | Jetpack Compose (BOM) + Material 3 | single-activity |
| Navigation | Compose Navigation | |
| Architecture | Multi-module: `app`, `core:*` (network, design system, data), `feature:*` (microscope, …) | MVVM, unidirectional data flow |
| DI | Hilt | |
| Concurrency | Kotlin Coroutines + Flow | |
| Networking | Retrofit + OkHttp + kotlinx.serialization | |
| API client | Generated from the FastAPI OpenAPI schema via `openapi-generator` (kotlin, `jvm-retrofit2`) | regeneration wired into Gradle; no hand-maintained DTOs |
| Images | Coil | |
| Local storage | Jetpack DataStore (settings, auth token) | Room only if/when offline caching becomes a feature |
| UVC capture | [`jiangdongguo/AndroidUSBCamera`](https://github.com/jiangdongguo/AndroidUSBCamera) (AUSBC) | fallback candidate: [`shiyinghan/UVCAndroid`](https://github.com/shiyinghan/UVCAndroid); both actively maintained as of 2026-08 |
| SDK levels | `minSdk 26`, `compileSdk`/`targetSdk` current (37 at scaffold time) | USB host mode required; graceful message on devices without OTG support |
| Testing | JUnit, MockK, Turbine, Robolectric; Compose UI tests | UVC hardware path verified manually against reference device `1b3f:2002` (cannot run in CI) |
| Static analysis | detekt + ktlint, Android Lint | |
| CI/CD | GitHub Actions (build, lint, unit tests, assemble); Renovate | APK distribution via GitHub Releases (Obtainium-friendly); Play Store deferred |

### Isolation rule for the UVC dependency

All UVC libraries in this space share fragile ancestry (forks of `saki4510t/UVCCamera`).
The chosen library is confined to the `feature:microscope` module behind a small
app-owned interface (device detection, permission, preview surface, single-frame JPEG
grab), so it can be swapped without touching the rest of the app.

## Consequences

- No code sharing with the React frontend; the OpenAPI schema becomes the single
  contract between app and backend.
- Contributors need Android/Kotlin knowledge; the JS toolchain stays out of this repo.
- Expo-style OTA updates are unavailable; releases ship as signed APKs.
- iOS remains out of scope (USB camera access on iOS is a separate, far more restricted
  effort — as noted in issue #1).
