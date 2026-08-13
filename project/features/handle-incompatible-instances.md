---
id: F-10
title: Keep working against instances the app only partly supports
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
        target: F-10 acceptance-1/2 ↔ F-4 acceptance-3
        resolution: proceed
      - kind: drift
        target: F-10 vs. spec/api/openapi-client-integration R-HEALTH-2, R-HEALTH-4
        resolution: proceed
      - kind: drift
        target: F-10 vs. spec/api/openapi-client-integration R-NEG-1, R-NEG-3
        resolution: proceed
      - kind: prior-art
        target: connection worktree — no health probe or version negotiation exists
        resolution: revisit-after feat/backend-connection merges to develop
---

## Description

Self-hosted instances drift. Someone runs a server six months behind the app, or the app
speaks an API major the instance has not reached. Neither should turn into a blank screen or
a crash, and — this is the part the first draft of this feature got wrong — neither should
turn into a refusal to connect.

The app tells the user plainly what it found and keeps working with what the instance can
actually do, switching off precisely the parts that need what is missing. There is one case
where refusal is the right answer rather than the lazy one: a certificate that does not
validate. An instance the app cannot verify is an instance it will not talk to, and the
message says so in terms of the certificate rather than blaming the app.

## Acceptance criteria

- [ ] **acceptance-1** An instance running a backend version below what the app needs produces a visible, localized warning and the app continues in a reduced mode rather than failing.
- [ ] **acceptance-2** The app uses the highest API major both it and the instance support, and refuses only when there is no major in common.
- [ ] **acceptance-3** An instance whose TLS certificate does not validate is not connected to, and the message points at the certificate rather than at the app.

## Test hooks

- **acceptance-1** — unit test over the `MIN_SUPPORTED` comparison using SemVer precedence with optional-`v` normalization, plus the reduced-mode transition — pending
- **acceptance-2** — unit test over major negotiation and the downward probe, including the no-common-major refusal — pending
- **acceptance-3** — instrumented test against an instance with a self-signed certificate; there is deliberately no bypass to disable — pending

**On R5's two-axis rule.** It gets no hook of its own, because acceptance-1 and acceptance-2
*are* the two axes — kept apart on purpose so neither can be read as implying the other. A
hook keyed to a requirement rather than an `acceptance-<n>` could never move from `pending`
to `passing`.

## Consistency notes

**This feature was reframed after the review, and the reframing is the important part.** As
drafted it was called "explain unusable instance" and its criteria described refusing an
instance whose version the app does not support. The reviewer found that this contradicts
`spec/api/openapi-client-integration/`. **One MUST and three softer requirements**, stated
at their real strength rather than levelled up: R-HEALTH-2 is a **MUST** and requires that
when `version < MIN_SUPPORTED` the app "show a visible, localized warning and continue in a
reduced mode … rather than hard-failing" — that alone is enough to defeat the refusal
framing. R-HEALTH-4 (**SHOULD**) adds that the disabling be precise rather than global,
R-NEG-1 (**MUST**) requires negotiating the highest common major, and R-NEG-3 (**SHOULD**)
adds the downward probe.

An earlier draft of this note called R-HEALTH-2 and R-HEALTH-4 "two MUSTs", which
misrepresented R-HEALTH-4 and contradicted this file's own later sentence describing it as a
`SHOULD`. The conclusion is unaffected — R-HEALTH-2 carries it by itself — but a reviewer
who checked the citation would have found it wrong.

The sharper point: **the refusal framing was not traceable to the requirement artefact
either.** R5 says only that the app "SHALL surface a clear localized diagnostic naming the
incompatibility and SHALL NOT crash". Nothing in R5 says refuse. The refusal was an
invention of the decomposition, and it survived my own drafting because it sounded decisive.
The spec wins, the artefact agrees, and the feature now describes degradation rather than
rejection. Only acceptance-3 keeps a refusal, because an unverifiable certificate is a
genuinely different case from an old server.

**Overlap with F-4 acceptance-3, resolution `proceed`.** F-4 — `gate-detection-availability`,
from roadmap item R-2 — asserts that on an instance too old to carry the detection endpoints,
the capture entry point is not offered "rather than failing on a missing route". That
criterion presupposes the app is *connected* to the old instance and degrades feature by
feature. The original F-10 refused the connection outright, which would have made F-4
acceptance-3 permanently unreachable: one could never arrive at a state of being connected to
an instance whose detection routes 404. Two different version comparisons were being
conflated — *below the app's minimum supported backend version*, which is this feature's
subject, and *supports the connection but not some later feature*, which is F-4's. Stated
directionally, mirroring F-4's own note: **F-10 gates the connection, F-4 gates a feature
within a connection**, and F-10 must not refuse instances F-4 exists to handle. The
reframing above resolves the contradiction rather than papering over it, but the boundary is
recorded here because it is easy to re-break. Merging the two was considered and rejected —
they sit in different roadmap items under different authorities.

**F-4's re-run has happened.** The feature spec called for one once an overlapping feature
was added elsewhere in `project/features/`; `gate-detection-availability.md` now carries the
appended `findings` block from that re-run, recording the F-4 acceptance-3 ↔ F-10
acceptance-1/2 overlap with resolution `proceed`. Nothing is outstanding.

**Nothing here is implemented.** There is no `/api/health` probe anywhere in the tree, no
version comparison and no major negotiation.

## Risks

- acceptance-1 rests directly on an unresolved risk in the requirement artefact: the published
  release asset reports `info.version` `1.0.0` regardless of the release tag. This was first
  seen against tag `v0.1.0` and **still holds for `v0.2.0`**, whose asset again carries
  `info.version: 1.0.0` — so the divergence is systematic rather than a one-off slip in a
  single release. What `MIN_SUPPORTED` is compared against is settled in the spec but remains
  untested against a running instance.
- "Reduced mode" is asserted here but its concrete shape — which features switch off, and how
  the user learns which — is defined only by R-HEALTH-4's `SHOULD`. This feature can pass
  while leaving the experience vague.
- Was blocked on backend release `v0.2.0`; it was published on 2026-08-13 with its
  `openapi.json` asset, so the remaining dependency is R-1 generating the client from it.

## References

- `project/requirements/backend-connection.md` — R5, R33
- `spec/api/openapi-client-integration/en.md` — R-HEALTH-2, R-HEALTH-4, R-NEG-1, R-NEG-3
- `project/features/gate-detection-availability.md` — the feature this one must not make unreachable
- [#8](https://github.com/nolte/kamerplanter-android/issues/8) — the originating issue
