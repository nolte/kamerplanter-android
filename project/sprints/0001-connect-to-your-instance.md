---
number: 0001
status: planned
started: null
ended: null
value_statement: A plant owner connects the app to their own kamerplanter instance.
artifact_ref: null
last_commit: null
roadmap_items: [R-1]
features: [F-6, F-7, F-8, F-9, F-10, F-11]
---

## Goal

After this sprint a plant owner reaches their own kamerplanter instance by whichever route
suits their situation — scanning the pairing code from the web UI, pasting an API key issued
out of band, or pointing the app at a light-mode instance that asks for nothing at all — and
the connection is still there the next time they open the app. Settings shows what is
connected and lets them change or remove it, without ever printing the secret back at them.
Where an instance is older than the app expects, they get told plainly and keep whatever
still works instead of a dead end.

Two things about this sprint's starting position are worth stating rather than discovering
later.

**Its external blocker cleared the day after it was planned.** Every criterion that needs an
HTTP request waited on backend release `v0.2.0` being published in `nolte/kamerplanter`,
because the `v0.1.0` asset carried no device-pairing paths and the client could not even be
generated. `v0.2.0` was published on 2026-08-13 with an `openapi.json` asset that carries
`/api/v1/auth/device-pairing` and `/api/v1/auth/device-pairing/redeem`, so the schema this
sprint generates from now exists. Generating and pinning it is the sprint's own first task,
not a wait on someone else.

**Four of its criteria are regression contracts.** Work on `feat/backend-connection` has
shipped behaviour that four criteria now guard rather than demand: verification-before-persist
(F-6 acceptance-3), restore-on-launch (F-8 acceptance-1), credential erasure on disconnect
(F-8 acceptance-6) and secret masking (F-9 acceptance-3). Each says so in its feature's
`## Test hooks`. Two further criteria are partly covered: F-6 acceptance-4's tenant-adoption
rule ships while its picker UI does not, and F-8 acceptance-2's Keystore cipher is committed
but has never executed — it cannot run under `./gradlew test` and has not been exercised on
the Pixel 7a, so it is **not** counted as passing. The connection state machine (R6/R29) and
the debug/release variant split (R34) also ship, but they are criterion-free by design and
are therefore not criteria at all. Every acceptance box in all six features is still
unchecked. The sprint's real work is everything that touches the network, plus the Settings
surface — the method chooser in particular does not exist at all today.

## Features

- [F-6](../features/connect-by-pairing-code.md) — status: ready — **carries `verifies_sprint_value: acceptance-1`**
- [F-7](../features/connect-by-api-key.md) — status: ready
- [F-8](../features/stay-connected.md) — status: ready
- [F-9](../features/manage-connection-in-settings.md) — status: ready
- [F-10](../features/handle-incompatible-instances.md) — status: ready
- [F-11](../features/connect-to-light-mode-instance.md) — status: ready

## Out of scope

- Pest detection and everything that reads a result — F-1 through F-5, from roadmap item R-2.
  They are the other half of the MVP and belong to a later sprint.
- The plant list and the plant detail screen — roadmap items R-4 and R-5.
- The 4 KB page-alignment defect that makes Android 15+ warn on install — roadmap item R-3,
  tracked as [#14](https://github.com/nolte/kamerplanter-android/issues/14). Independent of
  this sprint and of every other item.
- The email-and-password connection path, which left scope during requirements elicitation
  because `/api/v1/auth/login` gives native clients no refresh token. Tracked upstream as
  [nolte/kamerplanter#1134](https://github.com/nolte/kamerplanter/issues/1134).
- The `/connect` deep link — roadmap item R-7, tracked as
  [#13](https://github.com/nolte/kamerplanter-android/issues/13).

## Review notes

_Populated by `sprint-review` at closure._
