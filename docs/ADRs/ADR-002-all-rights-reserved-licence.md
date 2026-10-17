# ADR-002: License the project as All Rights Reserved, keeping the upstream MIT notice

- **Status:** Accepted
- **Date:** 2026-10-17
- **Issue:** #48 (repository baseline: choose a licence and add `LICENSE`)

## Context

The repository is public on GitHub and had no `LICENSE` file, so readers could not tell what
they were allowed to do with the code. Choosing a licence was left to the project owner (#48).

Two facts constrain the choice:

- **Most of the history comes from another project.** Ticketeer continues
  [event-ticket-system](https://github.com/AdamSimkinbgu/event-ticket-system), the Ben-Gurion
  University course project that the owner wrote with five co-authors. That project is published
  under the MIT License ("Copyright (c) 2026 Adam Simkin"). Every commit in this repository that
  also exists upstream comes from it.
- **Everything since the import is the owner's.** Every commit made in this repository since
  2026-07-08 (87 of them on 2026-10-17) was written by Naim Elijah.

MIT has one condition: its copyright notice and permission notice "shall be included in all
copies or substantial portions of the Software". The upstream `LICENSE` was deleted here in
`20c0f520` on 2026-07-08, so the repository was distributing MIT code without that notice.

## Decision

**The project is licensed All Rights Reserved**: Copyright (c) 2026 Naim Elijah. No one may use,
copy, modify, merge, publish, distribute, sublicense or sell it without written permission. The
only rights others have are the ones GitHub's Terms of Service give every public repository:
viewing it, and forking it within GitHub (section D.5).

**`LICENSE` also reproduces the upstream MIT notice** for the portions that come from
event-ticket-system. That restores the notice MIT requires. Those portions stay under MIT.

`pom.xml` records the licence in `<licenses>`, and the README's licence section points to
`LICENSE` and to this record.

## Consequences

**What this makes easy:**

- Readers know their rights: they can read and evaluate the code, and nothing more without
  permission.
- The owner keeps every option for their own work: relicensing it later, open-sourcing it, or
  licensing it commercially.
- The repository complies with the MIT condition again.

**What this makes hard, and what is accepted:**

- **The restriction does not reach the inherited code.** The upstream portions remain available
  under MIT, from event-ticket-system or from any copy that carries its notice. Anyone can still use
  that version of the code freely; only the changes made here are reserved.
- **Outside contributions need an agreement.** With no licence granted, a contributor's pull
  request comes with no rights to reuse it. Accepting one needs a written agreement, such as a
  contributor licence agreement, first.
- **Not an open-source project.** Tools and readers that look for an OSI licence will find none,
  and GitHub labels the licence "Other".
- **Third-party licences still apply.** Dependencies such as Spring and Vaadin keep their own
  licences (Apache 2.0). Files with their own licence header, such as the Maven wrapper scripts,
  stay under it.

**Revisit when any of these happens:**

- The owner wants outside contributors, or wants others to reuse or deploy the code.
- The project is to be sold or licensed commercially. A lawyer should review the licence then.
- The upstream co-authors agree in writing to relicense their code, which would let one licence
  cover everything.

## Alternatives considered

- **All Rights Reserved over the whole repository, without the MIT notice.** Rejected: it breaches
  the MIT condition for the inherited code, and the co-authors' code is not the owner's to
  reserve.
- **No `LICENSE` file at all.** Rejected: copyright law already reserves every right by default,
  but readers cannot tell that from a missing file, and the MIT notice would stay missing.
- **A permissive licence, such as MIT or Apache 2.0.** Rejected by the owner, who chose to keep
  all rights to their work. This is the easiest option to adopt later.
- **A copyleft licence (GPL or AGPL), or a source-available one (PolyForm Noncommercial, Business
  Source License).** Rejected for the same reason: each still grants others rights to use the
  code.
