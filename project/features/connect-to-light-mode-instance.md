---
id: F-11
title: Connect to a light-mode instance without credentials
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
      - kind: overlap
        target: this feature was bundled into F-7 as drafted
        resolution: split-out F-7, F-11
      - kind: drift
        target: R11 was claimed by the bundled draft but covered by no criterion
        resolution: proceed
      - kind: prior-art
        target: connection worktree — Connection.LightMode and Credential.None ship; the health probe does not
        resolution: revisit-after feat/backend-connection merges to develop
---

## Description

A kamerplanter instance can run in light mode: no accounts, no login, everything reachable
without authentication. It is what someone runs on a machine in their own flat when the
whole point is that nobody else can reach it. Connecting to one should be as simple as it
sounds — the server address and nothing else.

The interesting part is what must *not* happen. A light-mode instance has no credential to
attach, so requests must carry none; and an instance that is not in light mode must never be
connected to as though it were, because that would quietly produce a connection that fails on
the first real call.

## Acceptance criteria

- [ ] **acceptance-1** Pointing the app at a light-mode instance connects it with the server address alone, with no credential requested.
- [ ] **acceptance-2** Requests to a light-mode instance carry no authorization credential.
- [ ] **acceptance-3** An instance that is not in light mode is never connected to without a credential.

## Test hooks

- **acceptance-1** — end-to-end on the Pixel 7a against an instance started with `KAMERPLANTER_MODE=light` — pending
- **acceptance-2** — unit test over the interceptor asserting no `Authorization` header is attached while the stored connection is `Connection.LightMode`; this is R11's only verification — pending
- **acceptance-3** — unit test asserting a full-mode `/api/health` response never yields a light-mode connection — pending

## Consistency notes

**This feature exists because the reviewer refused a bundling I had chosen for
convenience.** The API-key path and this one were drafted as a single feature on the grounds
that both are "a form, a verification, no scanner". That is a UI similarity and nothing more:
this path has no secret, no tenant scope, no header and no masked hint. The domain model
already says so — `Connection.LightMode` carries only a base URL, and `Credential.None` is a
`data object` rather than a null precisely because "light mode has no secret" is a
structural fact.

**The split is what gives R11 a home.** In the bundled draft, R11 — no `Authorization`
header while a light-mode connection is active — was claimed by the feature's requirement
range but asserted by no criterion at all. Claiming coverage that does not exist is worse
than declaring an omission, because nothing downstream can tell the difference. acceptance-2
now carries it. Its user-visibility is admittedly indirect: a user cannot see a header. It
is kept as a criterion rather than demoted to a hook because it is the requirement most
likely to be violated silently once the interceptor lands, and a silent violation would send
a credential to an instance that never asked for one.

**acceptance-3 is the inverse guard**, added for the same reason: light mode is detected
from `/api/health`'s `mode` field, and misreading it would produce a connection that appears
to work and fails on the first authenticated call.

**Partly shipped.** `Connection.LightMode` and `Credential.None` exist and are unit-tested
on `feat/backend-connection`. The `/api/health` probe that would detect light mode does not
exist anywhere — `core/network/` has no HTTP call at all — and `SettingsScreen` routes
`Collecting.LightModeEntry` back to the not-connected body.

## Risks

- The requirement artefact records light-mode tenant semantics as `assumed` and unverified:
  whether a light-mode instance exposes tenant-scoped routes at all was never confirmed. If
  it does, R32's path handling and this feature's shape both need revisiting.
- acceptance-2 is the least observable criterion in R-1 and the most consequential when
  wrong.
- Was blocked on backend release `v0.2.0`, like every other network-bearing criterion; it was
  published on 2026-08-13, so the remaining dependency is R-1 generating the client from it.

## References

- `project/requirements/backend-connection.md` — R10, R11, R32
- `project/features/connect-by-api-key.md` — the half this feature was split from
- [#8](https://github.com/nolte/kamerplanter-android/issues/8) — the originating issue
