---
id: F-9
title: See and change your connection in Settings
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
      - kind: drift
        target: R-1 feature set — the three-method choice itself was uncovered
        resolution: proceed
      - kind: prior-art
        target: connection worktree — Connection.kt:106-110, SettingsScreen.kt:187-189 (acceptance-3)
        resolution: proceed
      - kind: overlap
        target: F-9 acceptance-5 ↔ F-8 acceptance-6
        resolution: proceed
      - kind: overlap
        target: F-9 acceptance-4 ↔ F-6 acceptance-3
        resolution: proceed
      - kind: drift
        target: R29 was attributed to this feature by range while also declared unclaimed
        resolution: proceed
---

## Description

Settings is where the connection lives once it exists — and where it begins when it does
not. With nothing connected, it offers the three ways in: scan the pairing code, paste an
API key, or point at a light-mode instance. With something connected, it says what that
something is: which server, reached how, as which tenant, signed in as whom where the
instance reports it.

What it never says is the secret. A user who wants to check they pasted the right key sees
the last few characters and no more. And because instances move, keys rotate and phones get
handed on, the connection can be swapped for a different one — by any method, not just the
one used first — or removed entirely, at any moment.

## Acceptance criteria

- [ ] **acceptance-1** With nothing connected, Settings offers all three ways to connect.
- [ ] **acceptance-2** With a connection in place, Settings shows the server address, the method in use, the tenant, and the signed-in identity where the instance reports one.
- [ ] **acceptance-3** No stored secret appears in the clear anywhere in Settings; at most a masked hint.
- [ ] **acceptance-4** The connection can be changed from any method to any other, and a successful change replaces the previous one entirely.
- [ ] **acceptance-5** The connection can be removed at any time.

## Test hooks

- **acceptance-1** — Compose UI test over the not-connected state; today `SettingsScreen.kt:99-101` routes both non-QR collection states back to a body offering only the QR button, so this is entirely new work — pending
- **acceptance-2** — Compose UI test; the model carries method, tenant and identity, but `ConnectedBody` currently renders only the base URL — new work, not a regression check — pending
- **acceptance-3** — **regression check.** `Connection.kt`'s `maskSecret` and its tests ship, and `SettingsScreen` carries an explicit comment that the dummy's pairing code was removed on purpose — pending
- **acceptance-4** — Compose UI test plus the existing `SettingsViewModelTest` coverage of atomic replacement — pending
- **acceptance-5** — Compose UI test over the disconnect affordance — pending
- **R35 (EN/DE strings)** — no criterion by design; every string added here needs both locales — pending

## Consistency notes

**acceptance-1 exists because of the sharpest finding in this pass.** The decomposition
deliberately left R6 (the three-kind connection model) and R29 (the state machine) without
criteria, on the theory that structure earns criteria only through its user-visible
consequences — and the reviewer largely agreed: F-6, F-8 and this feature between them walk
nearly every edge of the state diagram, and both requirements are already implemented and
tested, so the omission carried no implementation risk. But it found one narrow hole that
was easy to miss. F-6 asserts the QR path works, F-7 and F-11 assert their paths work, and
acceptance-4 asserts you can *change* method once connected. **Nothing asserted that the
initial choice is offered at all.** That is not hypothetical: `SettingsScreen.kt:99-101`
currently routes `Collecting.ApiKeyEntry` and `Collecting.LightModeEntry` straight back to a
body whose only button hard-codes `QR_PAIRING`. The method chooser is the one piece of R6's
user-visible surface that neither exists nor was demanded. acceptance-1 demands it.

**R29's attribution was contradictory and is fixed.** The draft attributed `R19, R26–R29` to
this feature while simultaneously listing R29 among the deliberate omissions. The range now
reads R19, R26–R28; R29 stays a declared, criterion-free structural requirement alongside R6.

**Overlap with F-8 acceptance-6, resolution `proceed`.** acceptance-5 here asserts the
connection can be removed; F-8 asserts that removal erases the stored credential completely.
One user action, two different observable objects — the affordance and its storage
consequence. An implementation can satisfy either while violating the other: an action
buried three screens deep still erases correctly, and a prominent action can leave ciphertext
behind. Merging them would hide whichever half failed. Settings owns the affordance;
persistence owns what happens underneath.

**Overlap with F-6 acceptance-3, resolution `proceed`.** acceptance-4 asserts a successful
change replaces the previous connection entirely (R27); F-6 asserts a failed attempt leaves
it untouched (R14). These are the two halves of one atomic-replacement behaviour, and
neither implies the other — replacing cleanly on success says nothing about what a failure
leaves behind. Both are needed, and they sit with the surfaces that own them.

**One criterion records shipped behaviour, three do not.** acceptance-3's masking is
committed and tested. acceptance-1 and acceptance-2 are entirely new: the chooser does not
exist and the connected view renders only the base URL.

## Risks

- This feature is almost entirely UI work in a module that `feat/backend-connection` is
  actively rewriting; sequencing it after that branch merges avoids a conflict.
- acceptance-2's "signed-in identity where the instance reports one" depends on what the
  redemption response actually carries, which is unverified against a running instance.
- The Compose hierarchy currently exposes no `content-desc` or `resource-id` anywhere, which
  makes every UI test here coordinate-based and brittle unless semantics are added alongside.

## References

- `project/requirements/backend-connection.md` — R19, R26–R28 (R29 declared, criterion-free)
- `project/goals.md` — O-1
- [#8](https://github.com/nolte/kamerplanter-android/issues/8) — the originating issue
