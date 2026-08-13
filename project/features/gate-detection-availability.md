---
id: F-4
title: Offer pest detection only where the instance supports it
status: ready
roadmap_item: R-2
sprint: 2
created: 2026-08-13
ended: null
verifies_sprint_value: null
consistency_check:
  - performed_at: 2026-08-13
    agent_version: feature-consistency-reviewer@92e056b
    findings:
      - kind: overlap
        target: F-4 acceptance-4 ↔ F-1 acceptance-1
        resolution: proceed
      - kind: prior-art
        target: spec/api/openapi-client-integration/en.md — R-HEALTH-1, R-HEALTH-4, R-COMPAT-3
        resolution: proceed
  - performed_at: 2026-08-13
    agent_version: feature-consistency-reviewer@104487c
    findings:
      - kind: overlap
        target: F-4 acceptance-3 ↔ F-10 acceptance-1/2
        resolution: proceed
---

## Description

Pest detection is off by default on a kamerplanter instance and has to be switched on by
whoever runs the server. Some instances answer with a local detector, some with a cloud
service that needs the user's consent first, and an older instance does not know the feature
at all. None of that is the phone owner's doing, and none of it should reach them as a
failed request.

So the app asks first and shapes itself around the answer: where detection cannot work, the
entry point is simply not there, with a sentence explaining why rather than a button that
errors. Where the instance sends images to a cloud service, the app obtains that consent
before a picture is ever taken — not before it is uploaded, because an image that was never
captured cannot leak. The wording of that consent comes from the instance itself, so nobody
is agreeing to text the app invented.

## Acceptance criteria

- [ ] **acceptance-1** When the instance reports detection unavailable or no adapter configured, the capture entry point is not offered.
- [ ] **acceptance-2** When the entry point is not offered, the user is told why.
- [ ] **acceptance-3** On an instance too old to carry the detection endpoints at all, the entry point is likewise not offered, rather than failing on a missing route.
- [ ] **acceptance-4** When the active adapter requires cloud consent and it has not been granted, no image is captured until that consent is obtained.
- [ ] **acceptance-5** The consent dialogue shows the purpose, description and legal basis exactly as the instance supplies them.
- [ ] **acceptance-6** On an instance whose detection runs locally, no consent is requested.

## Test hooks

- **acceptance-1** — unit test over the `GET /pest-detection/status` response mapping across `available`, `feature_enabled` and per-adapter `configured` — pending
- **acceptance-2** — unit test asserting an explanatory state rather than an absent one — pending
- **acceptance-3** — unit test driving a `404` from the detection routes, per `R-COMPAT-3` — pending
- **acceptance-4** — unit test asserting no capture is reachable while consent is outstanding — pending
- **acceptance-5** — unit test asserting the dialogue renders server-supplied strings verbatim, with no app-authored consent wording — pending
- **acceptance-6** — unit test with a local adapter reporting no `requires_consent` — pending

## Consistency notes

This feature did not exist during pass 1; it was created by that pass's blocking finding.
The reviewer found that F-1 and F-2 both owned whether the capture action is presented —
F-1 asserting the picker appears, F-2 asserting conditions under which it must not — so
neither was independently verifiable. Splitting the gate out resolved the ownership question
and gave requirement R5 a home, which it had lacked entirely.

**Overlap requiring rationale — acceptance-4 ↔ F-1 acceptance-1, resolution `proceed`.**
This feature asserts that no image is captured until consent is obtained, which constrains
the moment F-1 owns. The coupling is intrinsic, not accidental: requirement R3 deliberately
places the gate before capture rather than before upload, because the protection is
worthless once the picture exists. Folding the consent surface into F-1 would scatter a
GDPR-bearing interaction across features and dissolve this feature's reason to exist;
splitting it again would make a feature out of one criterion. The resolution is to record the
ordering — **F-4 gates F-1**, and F-1 is not independently acceptable — rather than to
pretend the boundary is clean. Note that acceptance-1 does *not* contest F-1: this feature
gates on instance configuration, F-1 on device attachment, which are different objects.

**Prior art — the API spec already settles a gating mechanism.**
`spec/api/openapi-client-integration/en.md` requires querying `GET /api/health` before
exercising features (`R-HEALTH-1`) and disabling precisely the features whose capability is
unavailable (`R-HEALTH-4`). This feature's gate is genuinely additional — the detection
status endpoint carries adapter and consent information the health endpoint does not — so it
layers on that mechanism rather than replacing it. The reviewer's second point produced
acceptance-3: the whole detection surface arrives with backend release `v0.2.0`, so an older
instance returns `404` on those routes instead of reporting "unavailable", and `R-COMPAT-3`
forbids assuming the endpoints exist.

**Re-run, 2026-08-13 (`feature-consistency-reviewer@104487c`) — triggered by a new
overlapping feature.** The decomposition of roadmap item R-1 added
[`handle-incompatible-instances`](handle-incompatible-instances.md) (F-10), which the
feature spec names as a re-run trigger: "a feature with overlapping scope is added …
elsewhere under `project/features/`". The finding was raised during that run rather than by
a separate pass dedicated to this file, since F-4 was in that reviewer's scope and it read
this feature's acceptance-3 directly; recorded here with its true provenance.

The overlap concerns **acceptance-3** — the criterion asserting that on an instance too old
to carry the detection endpoints, the entry point is not offered rather than failing on a
missing route. As F-10 was originally drafted, it refused the connection outright to any
instance running an unsupported version, which would have made acceptance-3 **permanently
unreachable**: one could never arrive at the state of being connected to an instance whose
detection routes answer `404`, because the connection would have been refused first. Two
different version comparisons were being conflated — *below the app's minimum supported
backend version*, which is F-10's subject, and *supports the connection but not some later
feature*, which is this criterion's.

F-10 was reframed rather than this criterion weakened: it now warns and continues in a
reduced mode, per `R-HEALTH-2` (a **MUST**) and `R-HEALTH-4` (a **SHOULD**) of
`spec/api/openapi-client-integration/`, keeping refusal only for a certificate that does not
validate. So acceptance-3 stays reachable and unchanged. The boundary is recorded on both
sides because it is easy to re-break: **F-10 gates the connection, F-4 gates a feature
within a connection.** Resolution `proceed`; nothing about this feature changed.

## Risks

- This feature places an Art. 6(1)(a) GDPR consent interaction inside the app. The wording
  comes from the instance, which contains the risk, but the app now owns the surface where
  the user actually consents.
- The consent path is only reachable when a self-hoster has enabled the cloud adapter, which
  is off by default — so it is the least-exercised path in the feature and the most
  consequential when wrong.
- Nothing here is implementable until R-1 delivers the generated client, which is itself
  blocked until backend release `v0.2.0` is published upstream.

## References

- `project/requirements/pest-detection.md` — R1, R2, R3, R4, R5
- `spec/api/openapi-client-integration/en.md` — R-HEALTH-1, R-HEALTH-4, R-COMPAT-3
- [#10](https://github.com/nolte/kamerplanter-android/issues/10) — the originating issue
