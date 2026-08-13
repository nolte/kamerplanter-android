---
id: F-6
title: Connect by scanning your instance's pairing code
status: ready
roadmap_item: R-1
sprint: 1
created: 2026-08-13
ended: null
verifies_sprint_value: acceptance-1
consistency_check:
  - performed_at: 2026-08-13
    agent_version: feature-consistency-reviewer@104487c
    findings:
      - kind: prior-art
        target: connection worktree — SettingsViewModel.kt:160-214 (acceptance-3)
        resolution: proceed
      - kind: prior-art
        target: connection worktree — SettingsViewModel.kt:173-187 (acceptance-4)
        resolution: proceed
      - kind: drift
        target: F-6 acceptance-2 vs. shipped R44 behaviour in QrPayloadParser.kt
        resolution: proceed
      - kind: drift
        target: F-6 — R43's return-to-scanner half was uncovered
        resolution: proceed
      - kind: duplication
        target: F-7 acceptance-4 (removed) duplicated F-6 acceptance-3
        resolution: merge-into F-6
      - kind: overlap
        target: F-9 acceptance-4 ↔ F-6 acceptance-3
        resolution: proceed
      - kind: prior-art
        target: connection worktree — no generated client exists; every network criterion is unimplemented
        resolution: revisit-after feat/backend-connection merges to develop
---

## Description

The fastest way onto a self-hosted instance is the pairing QR code its web UI shows: the
user points the phone at the screen, and the app has both the server address and a one-time
code without anyone typing a URL on a phone keyboard. What comes back is a real session, not
a stored password.

The path is deliberately careful about what it keeps. Nothing is written to the device until
the instance has confirmed the code, and a failed attempt leaves whatever was connected
before exactly as it was. Once the code is redeemed, the app resolves which tenant the
session belongs to — silently when there is only one, by asking when there are several.

## Acceptance criteria

- [ ] **acceptance-1** Scanning the pairing QR code shown in the instance's web UI connects the app to that instance.
- [ ] **acceptance-2** A payload that is recognisably a kamerplanter pairing code but carries a version the app does not know is refused with a message; a foreign QR code is ignored so scanning simply continues.
- [ ] **acceptance-3** Nothing is stored until the instance has confirmed the credential, and a failed attempt leaves an existing connection untouched.
- [ ] **acceptance-4** After a successful pairing the app adopts the only tenant automatically, and asks which one when there are several.
- [ ] **acceptance-5** An unknown, already-used or expired pairing code produces one and the same message, without revealing which case applied, and returns the user to the scanner rather than a dead end.
- [ ] **acceptance-6** A locked-out redemption states how long the lockout lasts, and a rate-limited one is distinguishable from it.

## Test hooks

- **acceptance-1** — end-to-end on the Pixel 7a against a real instance; this is the sprint's value-verifying criterion, and it cannot pass without the vendored schema, the generated client, the `/api/health` probe, the redemption call and tenant resolution all existing (requirements R1–R4) — pending
- **acceptance-2** — unit test over the parser's three-way outcome (foreign / unknown version / valid) — pending
- **acceptance-3** — **regression check, not new verification.** `SettingsViewModelTest` already asserts `a failed change leaves the previous connection in place` and `a failed verification stores no credential` on `feat/backend-connection` — pending
- **acceptance-4** — **half regression, half new.** The adoption rule ships and is tested (`SettingsViewModel.kt:173-187`); the tenant-picker UI does not exist — `SelectingTenant` currently renders the same spinner as `Verifying` — pending
- **acceptance-5** — unit test over the `401` mapping plus the return-to-scanner transition — pending
- **acceptance-6** — unit test over the `423` and `429` mappings — pending
- **acceptance-2** — additionally guards R44: the existing `QrPayloadParserTest` and the scanner's permission handling must keep behaving, since the foreign-QR branch of this criterion *is* R44's requirement — pending

## Consistency notes

**Prior art dominates three criteria.** The reviewer verified against `feat/backend-connection`
that acceptance-3 and the adoption rule behind acceptance-4 are committed and unit-tested.
They are recorded as regression contracts rather than new work, and their hooks say so. What
does **not** exist anywhere is a single HTTP call: `core/network/` provides a bare
`OkHttpClient` and nothing else, `ConnectionClient`'s release binding is
`UnavailableConnectionClient`, which refuses everything, and there is no vendored schema. So
every criterion needing a round trip is unimplemented, and the whole feature is
`revisit-after feat/backend-connection merges to develop`.

**acceptance-2 was rewritten because it collided with shipped behaviour.** As first drafted
it demanded a message for an unrecognised version. The shipped `QrPayloadParser` returns
`null` for every non-match, and `SettingsViewModel` drops `null` silently so scanning
continues — which is R44, deliberately. The criterion therefore needed a three-way outcome
it had been hiding: a foreign QR code keeps the scanner running, a kamerplanter payload of
unknown version is refused with a message, a valid one is redeemed. Today's parser cannot
express the middle case, and that design decision was buried inside a criterion that read as
settled. It is now explicit.

**acceptance-5 gained its recovery half.** R43 requires both the indistinguishable message
*and* returning the user to the scanner rather than a dead end; the criterion covered only
the first, which is the less user-visible of the two.

**Overlap with F-9 acceptance-4, resolution `proceed`.** F-9 asserts that a successful
change replaces the previous connection entirely; acceptance-3 here asserts that a failed
attempt leaves it untouched. These are the success and failure halves of one
atomic-replacement behaviour, traced to R27 and R14 respectively. Neither implies the other:
an implementation could replace atomically on success while corrupting state on failure, or
preserve state on failure while replacing partially on success. Merging them would produce a
criterion that half-passes, which the spec forbids. They stay in the features that own their
respective surfaces — the connection path owns the failure case, Settings owns the change
affordance.

**A duplication was removed rather than rationalised.** The API-key feature originally
carried "both paths verify against the instance before anything is stored", which restates
R13 exactly as acceptance-3 does. It was dropped; acceptance-3 owns R13 and states it more
completely. Note the reviewer's observation that R13's authenticated-call half does not apply
to light mode at all, so the removed criterion was not even accurate for the path it claimed.

**acceptance-1 lost a clause it should never have carried (2026-08-13).** For a short while
it read "…connects the app to that instance, **and** the connection is still there after an
app restart", added so that the sprint's value verifier would cover both halves of "connects
and stays connected". A pre-merge review rejected it on two counts, both correct: the
criterion carried two independently failable checks, which the feature spec forbids
("atomic — a single check"), and its restart half was word-for-word F-8 acceptance-1 while
neither feature's `consistency_check` recorded the overlap. The concrete failure it invited
was ugly: an implementation that connects but does not survive a restart would *half-pass*
the one criterion the sprint's value contract points at.

The clause is gone rather than split into a second criterion here, because F-8 already owns
persistence and a duplicate would recreate the overlap in a new place. Sprint 1's
`value_statement` was trimmed to match what one atomic criterion can prove; the "stays
connected" half is delivered by F-8 as a feature of the same sprint, verified by its own
criteria rather than by the sprint verifier.

**Requirement-pinned test hooks were re-pinned (2026-08-13).** The same review found hook
entries keyed to requirement IDs (`R1–R4`, `R44`) rather than to an `acceptance-<n>`
identifier. The feature spec requires every hook to pin to a criterion; entries that do not
can never move from `pending` to `passing`, which would have blocked
`in_progress → done` indefinitely. Both were folded into the criteria whose verification
actually depends on them.

## Risks

- The generated API client — the largest and most blocking item in R-1 — has no feature of
  its own by design, so its verification rides on acceptance-1. acceptance-1 is the criterion that cannot
  pass without it.
- Nothing in this feature is implementable until backend release `v0.2.0` is published
  upstream; the currently published `v0.1.0` asset carries no device-pairing paths.
- acceptance-2 depends on a parser rewrite that also changes the payload format from the
  dummy's custom-scheme URI to the versioned JSON object.

## References

- `project/requirements/backend-connection.md` — R7, R8, R13–R16, R40–R44
- `project/goals.md` — O-1
- [#8](https://github.com/nolte/kamerplanter-android/issues/8) — the originating issue
