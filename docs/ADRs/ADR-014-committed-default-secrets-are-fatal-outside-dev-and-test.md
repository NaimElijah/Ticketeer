# ADR-014: Committed default secrets are fatal outside dev and test

- **Status:** Accepted
- **Date:** 2026-10-30
- **Issue:** #185 (security scan), #34; related #77; extends [ADR-003](ADR-003-fatal-and-self-healing-init-failures.md)

## Context

`application.yml` gives every setting a `${ENV:default}` default, so that dev and the test suite
run with no environment at all. Two of those defaults are secrets, and this repository is public:
- `jwt.secret`, the token signing key;
- `platform.admin.password`, the password of the System Admin created at boot when none exists.

A run with neither variable set, on any profile but `dev`, therefore signed sessions with a key
anyone can read, and created its System Admin with the password `admin`. CI's production-artifact
job booted the jar exactly that way, and passed.

Forces:
- **ADR-003 makes a misconfiguration fatal.** It stops the boot with a report rather than starting
  in a degraded state, and the `@Validated` property records do that for out-of-range values. A
  committed secret is not out of range: it is a valid string that happens to be public.
- **A resolved property does not say where it came from.** Spring resolves `${JWT_SECRET:…}` to one
  string, so the check has to know the committed values themselves.
- **Slice tests bind the property records with no profile.** Removing the defaults from
  `application.yml` would break them, and `test` must keep running on committed values.
- **The configured password is not the stored one.** The admin is created only when none exists,
  and no admin password can be changed yet (#77). A database first started with the default keeps
  an admin whose password is `admin`, whatever `PLATFORM_ADMIN_PASSWORD` says later.

## Decision

- **`bootstrap/CommittedDefaultSecretsGuard`** refuses to start when neither `dev` nor `test` is
  active and either `jwt.secret` or `platform.admin.password` equals its committed default. The
  message names the key and the variable to set, never the value. The guard holds the two committed
  values as constants, and its test reads `application.yml` and fails if a default drifts from its
  constant.
- **The stored password is checked too.** Outside `dev` and `test`, before the platform
  initialises:
  - a default admin whose stored hash still matches the committed password gets the configured
    password, logged at WARN without either value;
  - any other admin whose hash matches the committed password stops the boot.
- **The same rule for the other boot-time inputs this scan tightened:** `wsep.base-url` must be
  https, since `pay` carries card data, and is refused at binding otherwise.
- **CI generates its secrets per run** (`openssl rand`), so no value in the repository could be
  reused for a deployment.

## Consequences

- **Easier:**
  - A deployment cannot come up with a credential published in the repository, either configured or
    left in the database.
  - The variables a deployment must set are listed in `.env.example`.
- **Harder:**
  - Every run outside `dev` and `test` needs `JWT_SECRET` and `PLATFORM_ADMIN_PASSWORD`, including
    local runs with no profile and the `supabase` profile.
  - The committed values exist twice, in `application.yml` and in the guard, held together only by
    the drift test.
  - The boot writes to the database when it rotates the default admin's password. That is the only
    boot-time write outside the seed engine.
  - The datasource credentials still fall back to committed values (`postgres`/`postgres`) with no
    profile. A local PostgreSQL needs them; a deployment sets its own.

## Alternatives considered

- **Delete the secret defaults from `application.yml`**, so a run without them fails at binding.
  Rejected: slice tests that bind the property records with no profile would fail, and the failure
  would be jjwt's weak-key error rather than a message naming the variable.
- **Require the variables to come from the environment**, by checking `JWT_SECRET` itself rather
  than `jwt.secret`. Rejected: a deployment may set the property in an external config file, which
  V3 2.a.i allows.
- **Refuse to start when the stored admin has the committed password**, instead of rotating it.
  Rejected: with no admin password change yet (#77), an operator would have to edit the hash by hand
  to start the platform at all.

## Revisit when

- The admin credential lifecycle lands (#77). The rotation then belongs to it, and boot can refuse
  instead.
- Secrets move to a secret manager, or `application.yml` stops carrying defaults for them.
- A third secret gains a committed default, or the datasource defaults need the same treatment.
