# ADR-016: Bounds on secret values are checked outside bind validation

- **Status:** Accepted
- **Date:** 2026-10-31
- **Issue:** #187 (identity scan), PR #232; extends [ADR-003](ADR-003-fatal-and-self-healing-init-failures.md)

## Context

[ADR-003](ADR-003-fatal-and-self-healing-init-failures.md) makes a configuration value that is
missing, malformed or out of bounds fatal, and raises it through bind validation
(`@ConfigurationProperties` + `@Validated`). Spring Boot's failure report then names the property.

`jwt.secret` is the HMAC key session tokens are signed with. jjwt refuses an HMAC key under 256 bits
(HS256's minimum), so the value needs a minimum length, and the first version of
`JwtProperties` expressed it as `@Size(min = 32)` on the binding record.

Forces:
- **The bind-validation failure report prints the rejected value.** Spring Boot 3.5.x's
  `BindValidationFailureAnalyzer` writes `Value: "<value>"` for each failed constraint, at ERROR, to
  the log sink. A deploy with a too-short `JWT_SECRET` would therefore write the signing key to the
  log, and an operator who fixes it by lengthening the same value would leave most of the live key
  there.
- **The rule still has to stop the boot.** A too-short key must not reach a running platform, and
  ADR-003 treats a bad configuration value as fatal.
- **The operator needs the property named.** jjwt's own refusal of a weak key holds the key's length
  in bits, not the property it came from.
- **Other secrets will gain bounds.** `platform.admin.password` and `DB_PASSWORD` are the obvious
  next candidates, and each would meet the same report.

## Decision

- **A bound on a secret value is checked outside bind validation.** The check sits in the
  constructor of the bean that uses the secret and throws `IllegalStateException` with a message
  that names the property and the rule, never the value or any part of it.
  - `JwtSessionManager`'s constructor checks `jwt.secret` against
    `JwtProperties.MIN_SECRET_LENGTH` (32 characters, 256 bits) and throws
    `jwt.secret (env JWT_SECRET) must be at least 32 characters, the 256 bits HS256 signing needs`.
  - The length is counted in characters. Each is at least one byte in UTF-8, so a secret that
    passes the check is never short of 32 bytes, and jjwt's `WeakKeyException` cannot follow it.
- **`@NotBlank` stays on the binding record.** A rejected blank value prints nothing useful, so
  `JwtProperties` still fails a blank `jwt.secret` at binding, by property name, as ADR-003 says.
- **`JwtProperties.toString()` is redacted,** since a record's own `toString` prints every
  component.
- **The rule for the next secret:** a new bound on `PLATFORM_ADMIN_PASSWORD`, `DB_PASSWORD` or any
  other secret key is not a binding constraint (`@Size`, `@Pattern`, `@Min`, a custom validator). It
  goes in the constructor of the consuming bean, with a message that carries the key and the rule.
- **ADR-003's table is unchanged in outcome.** The failure is still fatal and still stops the boot;
  only the place of the check moves.

## Consequences

- **Easier:**
  - A rejected `jwt.secret` leaves its name and the minimum in the log and none of its value.
  - The rule is stated once, here, and a reviewer has something to cite when a `@Size` appears on a
    secret.
- **Harder:**
  - A secret's rule no longer lives next to its binding. It is in the consuming bean's constructor,
    and a bean that gives up consuming the secret takes the rule with it.
  - The boot fails during bean creation, not at binding. The operator sees a
    `BeanCreationException` whose cause is the `IllegalStateException`, not Boot's formatted
    property report, and a slice test that binds only the record does not see the rule. Tests must
    start the consuming bean too.
  - The rule is enforced by convention and by tests, not by a type or an ArchUnit rule. A later
    `@Size` on a secret compiles and works, and leaks.
- **Enforced by:** `InitPropertiesValidationTest` (`jwt.secret` section): a 31-character secret
  stops the context, the failure and its causes name `jwt.secret`, and no text in them, the
  rejected values of a bind-validation error included, holds the secret; a blank or whitespace-only
  secret fails at binding; `JwtProperties.toString()` does not hold the secret.

## Alternatives considered

- **`@Size(min = 32)` on the record.** Rejected: it is the arrangement that leaks. The failure
  report prints `Value: "<secret>"` at ERROR.
- **A check in the record's compact constructor.** Rejected, on a belief that was not verified: a
  constructor that throws during binding reaches Boot as a bind failure, and Boot's bind failure
  analyzer may print the property's `Value:` for it as well. It was not run, and the safe course is
  to keep the check out of anything the binder calls.
- **Rely on jjwt's `WeakKeyException`,** with no check of our own. Rejected: the exception holds
  only the key's length in bits, so it does not name the property, and it ties the rule to one
  library's behaviour and message. It would stop the boot without leaking, but the operator would
  have to guess which value was meant. The explicit check keeps jjwt's refusal as a backstop that
  cannot fire.

## Revisit when

- Spring Boot masks the rejected value of a sensitive property in its bind-validation report, so a
  binding constraint is safe again.
- Secrets move to a secret manager that validates them before the application sees them.
- A second secret gains a bound, to confirm the rule here still fits and to consider a shared helper
  for the check.
