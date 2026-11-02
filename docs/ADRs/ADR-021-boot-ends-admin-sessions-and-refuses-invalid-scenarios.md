# ADR-021: The boot ends the sessions of admins on the committed password, and a scenario the parser refuses is fatal

- **Status:** Accepted
- **Date:** 2026-11-02
- **Issue:** #187 (bootstrap scan); extends [ADR-003](ADR-003-fatal-and-self-healing-init-failures.md) and [ADR-014](ADR-014-committed-default-secrets-are-fatal-outside-dev-and-test.md)

## Context

**Admin sessions and the committed password.** ADR-014 rotates a default admin whose stored password is
still the one committed in `application.yml`, and stops the boot when any other admin is on it. An admin
session is a row in `sessions` (ADR-012). A token stays valid while its row lives, for up to
`session.member-ttl-minutes` (24 h by default). Rotating the password did not end those rows, so a
session opened with the published password outlived the rotation. A refused boot changed nothing either:
once the operator replaced the other admin's hash and booted again, that admin's old sessions were still
valid. ADR-014's Consequences call the rotation "the only boot-time write outside the seed engine".

**A scenario file the parser refuses.** An `expect-error` step must name the exception it expects
(`error=<ExceptionSimpleName>`), so a negative check passes only on the guard it means. A line without
`error=` is malformed: the step has no defined outcome. ADR-003's table makes a missing or unreadable
initial-state file fatal in every profile, and a failing step fatal only while `seed.fail-fast` is on.
It has no row for a file that is read but cannot be parsed.

## Decision

### 1. The boot ends the admin sessions of admins on the committed password

`CommittedAdminPasswordRotation` runs outside `dev` and `test`, while the application context starts and
before the web server takes requests.

- **Rotating the default admin:** it hashes the configured password, deletes the admin's ADMIN-kind
  session rows (`SessionRepository.deleteAdminSessions`), and only then sets and stores the new hash.
- **Refusing the boot** when another admin is still on the committed password: it deletes each such
  admin's ADMIN-kind session rows, then stops the boot with a message that names the admins' ids and says
  their sessions have ended.
- **Member sessions stay.** A MEMBER row with the same numeric id belongs to a different principal
  (ADR-012), and so do other admins' rows and guest rows.
- **A failure to delete the sessions propagates** and stops the boot, with the data-access error.

These are boot-time writes beside the rotation itself, so the rotation is no longer the only boot-time
write outside the seed engine.

### 2. A scenario file the parser refuses is fatal in every profile

`ScenarioParser.parse` refuses an `expect-error` line with no `error=`, or a blank one, with an
`IllegalArgumentException` that names the line. `ScenarioRunner.readScenario` parses the whole file before
any step runs, in every seed mode that runs a scenario, and `reset` reads it before it wipes. So a file
the parser refuses stops the boot in every profile, whatever `seed.fail-fast` says, like a missing or
unreadable one. It is a malformed file, not a broken step: no step can run, and the developer fixes the
line (adds `error=`) and boots again.

| Failure | Raised as | Outcome |
|---|---|---|
| A requested initial-state file is read but the parser refuses it | `IllegalArgumentException` from `ScenarioParser`, through `ScenarioRunner` | Fatal, in every profile |

## Consequences

### Decision 1

**What this makes easy:**

- **No session opened with the published password outlives the boot that removes the password,** neither
  after a rotation nor after a refused boot that the operator then fixes.
- **The order survives a failure.** Sessions end before the new hash is stored: if ending them fails, the
  stored password is still the committed one, and the next boot does both steps again.

**What this makes hard, and what is accepted:**

- **The boot deletes session rows,** beside rewriting the default admin's hash.
- **A signed-in default admin signs in again** after the rotation, with the configured password.
- **A refused boot writes before it stops:** the other admins' sessions end even though the boot fails.
  That is the intent: nothing issued under the committed password should survive the operator's fix.
- **A session-store failure stops the boot with the data-access error,** not with the refusal message.

### Decision 2

**What this makes easy:**

- **A malformed scenario never half-runs,** and `reset` never wipes a database for a file it cannot run.
- **The refusal names the line,** so the fix is one edit.

**What this makes hard, and what is accepted:**

- **One malformed `expect-error` stops a `dev` boot,** although `dev` runs with `seed.fail-fast` off.
- **Only the first malformed line is reported;** each further one costs another boot.

## Alternatives considered

- **Leave old admin sessions to expire.** Rejected: a session opened with the published password would
  stay valid for up to a session lifetime (24 h by default) after the rotation, and after the refused
  admin's fix.
- **End the sessions after storing the new hash.** Rejected: if ending them failed, the next boot would
  find no admin on the committed password, and nothing would end those sessions.
- **End every admin's sessions on a rotation.** Rejected: other admins' passwords did not change.
- **On a refused boot, change nothing,** as before. Rejected: the refused admin's old sessions would
  survive the operator's fix.
- **Record a parse error as a step FAIL, so `seed.fail-fast` off carries on.** Rejected: the step has no
  defined outcome. Running the wrapped operation without an expectation, or skipping it, would let a
  negative check look checked when it is not.
- **Skip the malformed line with a WARN.** Rejected for the same reason: the check it carries would
  silently not happen.

## Revisit when

- The admin credential lifecycle lands (#77). Changing an admin's password from the platform should end
  that admin's sessions too, and the rotation moves there (ADR-014).
- Sessions stop being rows, for example stateless tokens with no `sessions` table. Ending them then needs
  a revocation list.
- Scenarios are written by people who cannot boot the platform to see a parse error, for example uploaded
  through the UI: then validate a file before it is used, and report every malformed line at once.
