# ADR-025: An expired session is an invalid token

- **Status:** Accepted
- **Date:** 2026-11-05
- **Issue:** #122; refines how [ADR-012](ADR-012-session-tokens-bound-to-typed-session-rows.md)'s decision 2 rejects a token

## Context

ADR-012 decision 2 rejects a revoked, mismatched or unknown token with `InvalidTokenException`, and an expired one with
`SessionExpiredException`. Both extended `DomainException` side by side.

Callers that treat a dead session as "sign in again" caught only `InvalidTokenException`. The 21 presenters under `ui/presenters`
that have a not-authenticated outcome map that exception to it, but an expiry fell through to their generic `RuntimeException` arm
and became a failure. The four inbox views ignore a failure, so clicking a row after the session had expired did nothing at all.
Six tests named an expiry but stubbed a revoked token, so nothing caught it.

#122 offered two remedies: map both exceptions in every presenter, or relate the two types.

## Decision

- `SessionExpiredException` extends `InvalidTokenException`. An expired token is an invalid token, so `catch (InvalidTokenException …)`
  handles every dead token, including expiries.
- `SessionExpiredException` keeps its own message, "Session expired; please re-authenticate", through a protected `InvalidTokenException`
  constructor that takes a whole message. `SessionExpiredExceptionTest` pins it.
- Code that must tell an expiry from a revocation catches the subtype first. Nothing in the repo needs to today.
- Tests that pin a non-expiry rejection use `assertThrowsExactly(InvalidTokenException.class, …)`, since `assertThrows` would now also
  pass on an expiry.

## Consequences

**What this makes easy:**

- A presenter, existing or added later, that treats a revoked token as a dead session treats an expired one the same way, with no
  second arm to forget.
- The eight multi-catches that named both exceptions simplify to one type.

**What this makes hard:**

- `assertThrows(InvalidTokenException.class, …)` no longer tells the two apart. Each test that cares must say so with
  `assertThrowsExactly`.
- `SessionExpiredException` is also how a guest path refuses a raw session id that isn't a live guest row (ADR-012 decision 4), so that
  refusal is now an `InvalidTokenException` too. A guest path must not map it to "sign in", since a guest has no account to sign in to.
  No presenter does today.
- The not-authenticated outcome is still a message, not a redirect: the visitor reads "please sign in again" and is signed out on the
  next navigation. A redirect, and presenters with no not-authenticated outcome at all, remain open on #122.

## Alternatives considered

- **Map both exceptions in every presenter.** Rejected: 21 presenters and about 47 catch arms to change, and the next presenter can
  repeat the bug by catching only one.
- **A new supertype that both exceptions extend, such as `SessionRejectedException`.** Rejected: every presenter would still have to
  change its catch, and the hierarchy would gain a type only to name what `InvalidTokenException` already means.

**Revisit when** a caller must answer an expiry differently from a revocation, such as a token-refresh flow. It then catches
`SessionExpiredException` first, and nothing else changes.
