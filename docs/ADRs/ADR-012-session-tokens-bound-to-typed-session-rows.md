# ADR-012: Session tokens are bound to a typed session row, and member paths accept member tokens only

- **Status:** Accepted
- **Date:** 2026-10-30
- **Issue:** #185 (security scan); related #34, #38, #75

## Context

Identity issues its own JWTs (`JwtSessionManager`). Each token carries `sub` (a user or admin id),
`role` (`MEMBER` or `ADMIN`) and `sid`, the id of a `sessions` row. Revoking a token means deleting
its row. A guest has no token: the raw session id of a guest row is the guest's credential.

Scan #185 found these gaps:

- **The row bound nothing but its existence.** `parseClaims` checked the signature, the expiry and
  that a row named by `sid` existed. It never compared `sub` with the row's user, and the row did not
  record whether it was a member's, an admin's or a guest's. So the token's identity and role rested
  on the signing key alone, and that key has a committed default (#34).
- **Admins and members share one id space.** The default admin is id 1, and so is the first member
  to register. Only the admin gates read the `role` claim. Every member path called
  `validateToken` and then `extractUserId`, so an admin's token acted as the member with the same
  id: their orders, refunds, inbox and profile.
- **A raw session id was accepted for any row.** `validateCredential` treated a member's or an
  admin's session id as a guest credential.

The application services, not the UI, are where authentication is enforced: each takes the token and
validates it. There is one driving adapter today (Vaadin), but the services are the boundary a second
one would use (OQ-25).

## Decision

1. **Each session row records its kind:** `GUEST`, `MEMBER` or `ADMIN` (`sessions.kind`, migration
   `V3`). A guest row has no user; the other two do. `promoteTo` makes a guest row a member row.
   `V3` deletes every existing row that has a user instead of guessing its kind: backfilling them
   from `user_id` would have turned live admin rows into member rows, which a token signed with the
   key could then use as the member with the admin's id.
2. **`parseClaims` binds a token to its row.** It rejects a token whose `sub` is not the row's
   user, whose `role` does not match the row's kind, that names a guest row, or whose row has
   expired. Rejection is `InvalidTokenException`, as for a revoked token, or
   `SessionExpiredException` for an expired row, as for an expired `exp` claim.
3. **The default validation is member-only.** `validateToken`, `extractUserId` and the token path
   of `validateCredential` accept member tokens only. An admin's token fails them as a revoked token
   would. Admin gates call `validateAdminToken`, and `extractAdminId` where they need the id. Code
   that forgets to choose therefore gets the narrower answer. An `ADMIN` row is created only by admin sign-in, and no admin
   can be deleted yet, so the binding is enough: the gates added here do not look the admin row up.
   The older gates in `SystemAdminService` and `MessagingService` still do. `existsByUserId`, behind
   `isOnline`, counts member rows only.
4. **A raw session id is a credential only for a live guest row.** Every guest service path checks
   it through `validateGuestSession`, which accepts neither a member's raw id nor a member's token,
   and ending a guest session deletes guest rows only.
5. **No notice is addressed to an admin id** until notifications carry a recipient type (#217).
   Admin ids collide with member ids in the notification store, and admins have no inbox, so every
   such notice reached a member.

## Consequences

- **Easier:**
  - A token is valid only for the principal and role its row was created for, even to someone who
    holds the signing key.
  - A new member path is safe without a role check of its own.
- **Harder:**
  - Everyone signed in when `V3` runs, members and admins alike, signs in again. Sessions are
    transient (at most `session.member-ttl-minutes`), so this costs one sign-in.
  - `V3` is an exception to ADR-007's expand-only rule. `kind` is NOT NULL with no default, because
    no single kind fits both the guest and the member rows an older instance inserts. An instance
    still running the previous version therefore cannot write sessions until it stops. That is
    acceptable while the platform runs one instance and nothing is deployed (ADR-007, Context);
    a rolling deploy would need the column added nullable first.
  - Deleting or disabling an admin, once it exists (#77), must delete that admin's session rows,
    because the gates added here rely on the row alone.
  - Admins get no notification of complaints or replies until #217.
  - An admin who opens the storefront is a guest there, not a member.
  - `SessionManager` has two validation families, member and admin, to keep apart.

## Alternatives considered

- **Disjoint id ranges for admins, such as an offset or negative ids.** Rejected:
  - It needs a data migration of `admins` and of every stored reference to an admin id (messages,
    conversations).
  - It still leaves the `role` claim unbound to the row.
  - It makes the type of a principal depend on an arithmetic convention.
- **A role check at each member call site.** Rejected:
  - There are about a dozen such sites.
  - The default stays fail-open, so a new site that forgets the check reopens the gap.
- **Separate signing keys per pool.** Rejected: it needs a second secret, and it does not bind
  `sub` to the row.
- **Spring Security's authentication model.** Out of scope here; that is the decision #38 records.
- **One identity-owned check that every admin gate calls** (for example `requireLiveAdminId`).
  Deferred: it would route the gates through identity's application service, a reach #54 is
  removing. Identity's inbound port (#54) is where that check belongs.

## Revisit when

- A second driving adapter (an HTTP API, OQ-25) accepts tokens from clients.
- Spring Security replaces the custom guard (#38).
- Admins and members merge into one principal model.
- Notifications gain a recipient type (#217): decision 5 then lifts.
- Admins can be deleted or disabled (#77).
