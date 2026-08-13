---
mission_statement: >-
  kamerplanter-android lets a plant owner identify a pest from a USB-microscope
  photograph against the kamerplanter instance they run themselves.
relevant_outcomes: [O-1, O-2, O-5]
audiences:
  - Plant owner / home grower
  - Self-hoster (kamerplanter instance operator)
verifies_via: F-2:acceptance-1
time_bound:
  kind: mvp_completion
mvp_status: defining
created: 2026-08-13
revised_at: null
---

# Mission

## Statement

kamerplanter-android lets a plant owner identify a pest from a USB-microscope photograph
against the kamerplanter instance they run themselves.

- **Specific** — `mission_statement` names both what the app does (identify a pest from a
  microscope photograph) and for whom (the plant owner, an entry on the `audiences` list).
- **Measurable** — `verifies_via` names `F-2:acceptance-1`. When that single acceptance
  criterion is checked, the mission is measurably achieved; no other verification mechanism
  is introduced.
- **Achievable** — the MVP is two of the seven queued roadmap items: `R-1` (connect to a
  self-hosted instance, including the generated API client) and `R-2` (identify a pest from
  a microscope or phone photo). This letter is the one without an anchor in this file's
  frontmatter: MVP membership lives in the roadmap's `mvp` flags, which `roadmap-plan` owns.
  Those flags are still `false` on every item, because an `mvp: true` item must also carry
  `detail: fine` and a non-null `target_sprint`, and no sprint exists yet. That gap is
  precisely why `mvp_status` is `defining` and not further along.
- **Relevant** — `relevant_outcomes` binds O-1, O-2 and O-5, each defined in
  [`goals.md`](goals.md). O-2 is the purpose, O-1 its precondition, and O-5 the benefit
  `R-1` delivers to the self-hoster alongside it.
- **Time-bound** — `time_bound` is `{ kind: mvp_completion }`: the mission is bound to the
  moment every MVP item is done and the verifying criterion is checked. Calendar dates are
  forbidden by the mission spec, and rightly so at this scale.

## Audiences

**Plant owner / home grower.** The plant owner is who this MVP is for. They connect the
phone to their own kamerplanter instance once and stay connected across restarts, without
typing a server address on a phone keyboard. When something looks wrong on a leaf, they put
it under the USB microscope, capture it at the sensor's full resolution, and their own
instance tells them what it thinks it is — marked on the image, named, with a confidence.
When it is not sure, it says so plainly rather than guessing, and when it recognises a
beneficial insect it says not to treat it. What the MVP does not give them yet is the
surrounding plant care: no list of their plants, no detail view, no diary.

**Self-hoster (kamerplanter instance operator).** The self-hoster runs the instance the app
talks to, and the MVP is built so their configuration decides what the app offers. They can
connect a device the way that suits their setup — the pairing QR code from the web UI, an
API key issued out of band, or nothing at all on a light-mode instance. Where their instance
is too old for the app, or where detection is switched off or has no adapter, the app says
so in plain words instead of failing on a missing route. Their audience artefact records the
expectation that the app documents its minimum backend version; the MVP meets that by naming
the version it needs and refusing clearly below it.

## Verification

The mission is verified by **`F-2:acceptance-1`** — the first acceptance criterion of
[`receive-pest-identification`](features/receive-pest-identification.md), which is the only
end-to-end criterion for O-2 across the feature corpus and carries that feature's
`verifies_sprint_value`. Verbatim:

> An image captured through the USB microscope at the device's largest offered mode is
> identified by the connected instance, and at least one finding is listed on the phone.

Checking it requires the whole chain to work at once — a connection to a self-hosted
instance, a microscope capture, an upload the instance accepts, and a rendered finding — so
it cannot pass while any part of the MVP is missing.

## Source

- Audience artefact: `AUDIENCES.md` at commit `1a727afe14e1a1dc2ed11b5b82de3c68dba67b4e`.
- Outcomes consulted: `project/goals.md` (O-1, O-2, O-5).
- Verifying feature: `project/features/receive-pest-identification.md` (`F-2`,
  `verifies_sprint_value: acceptance-1`).
- Operator: nolte via the `mission-define` skill, commit-pending.
