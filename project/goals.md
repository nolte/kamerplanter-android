# Vision

kamerplanter-android is the native Android companion to a self-hosted kamerplanter
instance. It exists so that a plant owner who spots something odd on a leaf can settle
the question where the plant actually stands — phone in one hand, USB microscope on the
leaf — instead of carrying a sample to a desktop. The app resolves what the eye cannot,
hands the image to the owner's own kamerplanter server for identification, and keeps the
surrounding plant care close enough that opening the app is worth it daily. Everything it
does runs against an instance the user operates themselves; the app never holds their
data, only their connection to it.

## Outcomes

<!--
Outcome IDs are monotonic and never reused across the project's lifetime, even after an
outcome is removed. Every outcome states an end-user benefit and cites the audience it
serves; audiences resolve to entries in AUDIENCES.md.
-->

- **O-1** — A plant owner connects the app to their own kamerplanter instance and stays
  connected across restarts, without typing a server URL or credentials on a phone
  keyboard. _(audience: plant owner / home grower, self-hoster)_
- **O-2** — A plant owner photographs a suspected pest through a USB microscope and gets
  their instance's identification back on the phone. _(audience: plant owner / home grower)_
- **O-3** — A plant owner sees at a glance which of their plants need attention, and can
  open any one of them for its full picture. _(audience: plant owner / home grower)_
- **O-4** — A plant owner records what they observed on a plant, with photographs, so the
  entry is still useful weeks later. _(audience: plant owner / home grower)_
- **O-5** — A self-hoster can tell which kamerplanter versions the app works with, and
  gets a clear message instead of a failure when their instance is too old.
  _(audience: self-hoster)_
- **O-6** — Someone installing the app from GitHub Releases gets a build that installs and
  runs without system warnings on current Android devices.
  _(audience: sideload installer, plant owner / home grower)_

<!--
Deliberately not an outcome yet: the community-garden administrator. That audience is
`assumed` in AUDIENCES.md with an unresolved open question about multi-tenant support, and
the connection work explicitly excludes multi-account. Recording a goal for it would assert
an intent nobody has confirmed. Revisit when that open question is answered.
-->
