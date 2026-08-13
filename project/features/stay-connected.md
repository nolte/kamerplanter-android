---
id: F-8
title: Stay connected across restarts and expiring tokens
status: ready
roadmap_item: R-1
sprint: 1
created: 2026-08-13
ended: null
verifies_sprint_value: null
consistency_check:
  - performed_at: 2026-08-13
    agent_version: feature-consistency-reviewer@104487c
    findings:
      - kind: prior-art
        target: connection worktree — DataStoreConnectionStore.kt:36-71 (acceptance-1)
        resolution: proceed
      - kind: prior-art
        target: connection worktree — KeystoreSecretCipher.kt:33-93 (acceptance-2)
        resolution: proceed
      - kind: prior-art
        target: connection worktree — SettingsViewModel.kt:148-158 (acceptance-6)
        resolution: proceed
      - kind: drift
        target: F-8 — R24's server-side session end was uncovered
        resolution: proceed
      - kind: overlap
        target: F-9 acceptance-5 ↔ F-8 acceptance-6
        resolution: proceed
      - kind: prior-art
        target: project/requirements/backend-connection.md:300-306 — the R17 open risk is now stale
        resolution: proceed
---

## Description

A connection the user has to re-establish is not really a connection. Once they have paired
the phone with their instance, it should still be paired next week — across app restarts,
across the fifteen minutes after which the access token expires, across the phone being put
down and picked up again.

The secret that makes this possible never sits readable on the device. It is encrypted under
a key the hardware holds and will not hand out, and the app renews it quietly in the
background. When that quiet renewal stops working — the session was revoked, the token
rotated out from under it — the app says so plainly and points at where to fix it, rather
than failing every request in silence.

## Acceptance criteria

- [ ] **acceptance-1** A connection survives an app restart; the user is not asked to reconnect.
- [ ] **acceptance-2** Secrets are stored encrypted under a device-backed key and are never readable in the clear from app storage.
- [ ] **acceptance-3** An expired access token is renewed without the user noticing.
- [ ] **acceptance-4** When renewal fails or the instance rejects the credential, the app returns to a disconnected state and says where to fix it.
- [ ] **acceptance-5** Disconnecting ends the session on the instance as well, not only on the device.
- [ ] **acceptance-6** Disconnecting removes the stored credential completely.

## Test hooks

- **acceptance-1** — **regression check.** `SettingsViewModelTest` already asserts `starts connected when a connection is already persisted`; the non-secret half ships end to end — pending
- **acceptance-2** — **on-device only.** `KeystoreSecretCipher` cannot execute under `./gradlew test`, since the JVM has no Keystore. This needs an instrumented or manual Pixel 7a step; `CredentialStoreContractTest` covers the seam's contract through an in-memory fake, not the encryption — pending
- **acceptance-3** — unit test over the refresh path (body transport, rotated token persisted) — pending
- **acceptance-4** — unit test over the `401` teardown and the refresh-failure path — pending
- **acceptance-5** — unit test over the session-delete call; R24 forbids `/auth/logout`, which rejects native clients with `403` — pending
- **acceptance-6** — **regression check.** `SettingsViewModelTest` asserts `disconnect removes the credential as well as the connection`, erasing the secret before the connection record — pending

**Deliberately criterion-free requirements.** R22 (refresh-token rotation) and R34 (the
debug-variant fake client) carry no acceptance criterion and therefore no hook — a hook keyed
to a requirement rather than an `acceptance-<n>` could never move from `pending` to
`passing`. Rotation is not user-visible, but acceptance-3 fails without it: a stale token
after rotation breaks the *next* renewal. R34 already ships and is guarded by
`ReleaseConnectionClientTest`, which asserts the fake is absent from the release classpath.

Two further requirements of this artefact — R36 (unit tests over the state machine, the
parser and the storage seam) and R37 (`task lint` / `task test` green plus the Pixel 7a
run) — are project-wide quality gates rather than behaviour of any one feature. They are
recorded here because sprint 1 is where they first bind: R36's three subjects all live in
this sprint, and R37's device run is pinned to F-6 acceptance-1.

## Consistency notes

**Two criteria record shipped, tested behaviour — and a third only half of that.** The
reviewer verified against `feat/backend-connection` that persistence and restore-on-launch
(acceptance-1) and complete credential erasure on disconnect (acceptance-6) are committed
**and** unit-tested; their hooks are marked as regression checks so nobody budgets for work
that is done. acceptance-2 is deliberately not in that group: the Keystore-backed AES-256-GCM
cipher is committed, but no test anywhere executes it — `KeystoreSecretCipher` cannot run
under `./gradlew test`, and `CredentialStoreContractTest` exercises an in-memory fake of the
seam rather than the encryption. Counting it as done would book the sprint's only security
criterion against evidence that does not exist; it stays open until it runs on the Pixel 7a.

**acceptance-5 was added because R24 was claimed but uncovered.** The draft's disconnect
criterion covered local erasure only. R24 requires ending the session on the instance via
`DELETE /api/v1/users/me/sessions/{key}` — and explicitly forbids `POST /auth/logout`, which
answers native clients with `403` for want of a CSRF cookie. A device that forgets its token
while the instance keeps the session alive is a materially different and user-relevant
outcome from one that ends it, so it earns its own criterion rather than a footnote.

**Overlap with F-9 acceptance-5, resolution `proceed`.** F-9 asserts the connection can be
removed at any time; acceptance-6 here asserts that removing it erases the stored credential
completely. Same user action, two different observable objects — the affordance and its
storage consequence. An implementation could offer the action and leave ciphertext behind,
or erase correctly while burying the action three screens deep; each criterion catches a
failure the other misses. They stay in the features that own their surfaces: Settings owns
the affordance, this feature owns what persistence does about it.

**A stale entry in the requirement artefact, since corrected.** The reviewer noted that
`backend-connection.md`'s surviving-risk entry for R17 still said the concrete encryption
mechanism "stays with the implementing specialist" — but `KeystoreSecretCipher` had since
made that choice, with no new catalog dependency. That entry has been struck through and
marked resolved in the artefact; nothing is outstanding here.

## Risks

- acceptance-2 is verifiable only on hardware, so it is the criterion most likely to be
  assumed green without evidence. The Keystore path has never run on the Pixel 7a.
- acceptance-3 and acceptance-5 both need HTTP calls that do not exist yet; nothing in
  `core/network/` makes a request today.
- Was blocked on backend release `v0.2.0`; it was published on 2026-08-13 with its
  `openapi.json` asset, so the remaining dependency is R-1 generating the client from it.

## References

- `project/requirements/backend-connection.md` — R17, R18, R20–R25, R34
- `project/goals.md` — O-1
- [#8](https://github.com/nolte/kamerplanter-android/issues/8) — the originating issue
