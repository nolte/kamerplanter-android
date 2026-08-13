---
id: F-7
title: Connect with an API key
status: draft
roadmap_item: R-1
sprint: null
created: 2026-08-13
ended: null
verifies_sprint_value: null
consistency_check:
  - performed_at: 2026-08-13
    agent_version: feature-consistency-reviewer@104487c
    findings:
      - kind: overlap
        target: F-7 as drafted bundled the API-key and light-mode paths
        resolution: split-out F-7, F-11
      - kind: duplication
        target: F-7 acceptance-4 ↔ F-6 acceptance-3
        resolution: merge-into F-6
      - kind: prior-art
        target: connection worktree — no generated client; the verification round trip is unimplemented
        resolution: revisit-after feat/backend-connection merges to develop
---

## Description

Not everyone has the web UI in front of them when they set the phone up. A kamerplanter
instance can issue an API key out of band — for a headless setup, a shared device, or simply
because the user would rather paste a string than scan a screen — and this path accepts one
together with the server address.

An API key differs from a paired session in a way the user should never have to think about:
it already knows which tenant it belongs to, so the app takes that from the key instead of
asking, and it never expires the way an access token does. What the user does see is that
the key, once accepted, is never shown back to them in full.

## Acceptance criteria

- [ ] **acceptance-1** Pasting a `kp_sk_` API key together with the server address connects the app to that instance.
- [ ] **acceptance-2** The tenant the key is scoped to becomes the connection's tenant, without the app asking which one.
- [ ] **acceptance-3** A key the instance does not accept leaves the app unconnected, with a message that does not echo the key back.

## Test hooks

- **acceptance-1** — end-to-end on the Pixel 7a with a key issued by a real instance — pending
- **acceptance-2** — unit test asserting the tenant is taken from the key's own `tenant_scope` rather than from a `GET /tenants` lookup — pending
- **acceptance-3** — unit test over the rejection path, asserting the message contains no substring of the supplied key — pending
- **R30–R32 (credential seam and interceptor)** — no criterion by design; the seam that attaches this key to requests is verified by acceptance-1 working at all — pending

## Consistency notes

**This feature was split out of a bundled draft, on the reviewer's recommendation and
against my initial framing.** The original F-7 covered both the API-key path and the
light-mode path, on the reasoning that they share a shape — a form, a verification, no
scanner. The reviewer answered the question directly: that similarity is a *UI* similarity
and nothing more. Semantically the API-key path has a secret, a `tenant_scope`, an
`Authorization` header and a masked hint; light mode has none of those, and
`Connection.LightMode` carries nothing but a base URL — `Credential.None` exists as a domain
member precisely because that emptiness is structural rather than accidental. Two further
arguments settled it: the project's own roadmap checklist lists "API key path" and
"Light-mode path" as separate entries, so the split was already made upstream; and
requirement R11 had no criterion anywhere, which is exactly the kind of gap bundling
produces. The counter-argument — two thin features in a hobby-scale repo, both blocked on
the same client — was weighed and rejected on the R11 gap. The light-mode half is now
[F-11](connect-to-light-mode-instance.md).

**A duplicated criterion was removed.** The draft carried "both paths verify against the
instance before anything is stored", which restates R13 exactly as F-6 acceptance-3 does.
Two criteria that pass and fail together are the latent overlap the consistency check exists
to catch. F-6 owns R13 and states it more completely, including the failure half. The
reviewer also noted the removed criterion was inaccurate for the light-mode half anyway,
since R13's authenticated-call requirement does not apply where there is no credential.

**Nothing here ships yet.** Unlike the pairing feature, no part of this path is implemented:
`SettingsScreen` currently routes `Collecting.ApiKeyEntry` straight back to the
not-connected body, which offers only the QR button. The form does not exist.

## Risks

- The whole path depends on the generated API client, which does not exist and is itself
  blocked until backend release `v0.2.0` is published upstream.
- acceptance-3's "does not echo the key back" is a security-relevant negative that is easy
  to violate accidentally through a generic error message that interpolates the input.

## References

- `project/requirements/backend-connection.md` — R9, R14, R15, R19
- `project/features/connect-to-light-mode-instance.md` — the half this feature was split from
- [#8](https://github.com/nolte/kamerplanter-android/issues/8) — the originating issue
