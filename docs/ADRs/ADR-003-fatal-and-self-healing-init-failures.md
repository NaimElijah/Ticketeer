# ADR-003: Platform initialization fails startup, except for an external-service outage

- **Status:** Accepted. Extended by [ADR-016](ADR-016-bounds-on-secret-values-are-checked-outside-bind-validation.md) (bounds on secret values are checked outside bind validation).
- **Date:** 2026-10-18
- **Issue:** #27 (fail startup on invalid init config or initial-state file)

## Context

V3 requires that the system does not come up when its initialization is invalid (V3 2.a, test 4.b),
that initialization parameters come from external configuration, and that a failing step of an
initial-state file fails the initialization (V3 2.a.ii). V3 also requires the platform to keep
working through, and recover from, the loss of an external service (SLR.5, SLR.6).

Before this decision, startup ignored every initialization failure:

- `PlatformInitializationRunner` caught every `DomainException` from `initializePlatform()`,
  logged it and let the process boot with the market closed.
- `initializePlatform()` checked the external services (WSEP) first. While they were down, the
  default-admin and integrity checks never ran. `ensureMarketOpen()` then retried initialization
  every self-heal tick and swallowed whatever it threw, so a missing admin or corrupt data
  surfaced only as a WARN every 30 seconds and a market that never opened.
- Configuration was read with `@Value` and never validated. A negative reservation hold was
  silently replaced by 10 minutes (`CartLineItem.setExpirationLimit`), a zero handshake count was
  clamped to 1, and a non-positive self-heal delay failed at context refresh without naming the
  property.
- `seed.fail-fast` defaulted to `false`, a missing scenario file only logged an error, and an
  aborted scenario was logged and ignored.

## Decision

**A failure is fatal unless waiting can fix it.** Fatal failures abort startup with an exception
that names the cause; the one self-healing failure leaves the platform up with the market closed.

| Failure | Raised as | Outcome |
|---|---|---|
| A configuration value is missing, malformed or out of bounds | Bind validation (`@ConfigurationProperties` + `@Validated`) | Fatal |
| A requested initial-state file is missing or unreadable | `IllegalStateException` from `ScenarioRunner` | Fatal |
| A step of the initial-state file fails while `seed.fail-fast` is on | `SeedAbortException` | Fatal |
| No System Admin exists and the default one cannot be created | `MissingDefaultAdminException` | Fatal |
| No System Admin is present after creation | `InitializationIntegrityException` | Fatal |
| The persisted state breaks a structural invariant | `SystemIntegrityViolationException` | Fatal |
| No payment service or no ticket issuer is reachable | `ExternalServiceUnavailableException` | Self-healing: logged at WARN; `MarketSelfHealScheduler` retries until WSEP answers |

**Initialization runs the fatal checks before the external-service check:** default admin, admin
present, structural integrity, then the WSEP quorum. A WSEP outage at boot can no longer hide a
fatal condition. `verifyInitializationInvariants()` keeps only the admin check; its two
reachability re-checks repeated the quorum check that now runs last.

**A failed default-admin insert is fatal only if no admin exists afterwards.** Several instances may
boot against one database (the `supabase` profile allows it); when two race on an empty admin table,
the loser's insert fails but the winner's admin exists, so the loser carries on.

**`PlatformInitializationRunner` catches only `ExternalServiceUnavailableException`.** Everything
else propagates out of the `ApplicationRunner`, so `SpringApplication.run` fails and the process
exits.

**Initialization parameters bind to one validated record per prefix,** owned by the module that
reads them: `constants` (sales), `session` and `auth.lockout` (identity), `wsep` (shared),
`platform.admin` (governance), `seed` and `market` (bootstrap). `@ConfigurationPropertiesScan` on
the application class registers them; an invalid value fails the context before any runner starts.
Every bound key has a default in `application.yml`, and each stays overridable from the environment
or an external file (`spring.config.import`, `--spring.config.additional-location`).

**`seed.fail-fast` defaults to `true`; the `dev` profile sets it to `false`.** A developer can keep
working while a demo-scenario line is broken; every other profile treats a requested state file as
part of initialization. A requested file wins over WSEP availability: if seeding needs a purchase
while WSEP is down, the scenario fails and startup fails with it.

## Consequences

**What this makes easy:**

- **A misconfigured deployment fails at once,** with Spring Boot's bind-failure report naming the
  property, instead of running with a silently substituted value.
- **Bad data stops the boot instead of a market that never opens.** The operator sees the cause in
  the startup failure, not in a WARN repeated every 30 seconds.
- **Startup tests are direct.** `ApplicationContextRunner` proves each bound, and the runners'
  unit tests prove which failures propagate.

**What this makes hard, and what is accepted:**

- **Existing data that breaks an invariant now blocks startup.** A database already carrying, say,
  an appointment cycle from #126 will not boot until it is repaired. That is the intent: opening a
  market on corrupt state is worse. Run the integrity check against a copy before deploying this to
  an environment with data.
- **Seeding outside `dev` needs WSEP.** A `.scenario` that buys tickets aborts startup while WSEP
  is unreachable.
- **Self-heal retries repeat the structural checks.** While WSEP is down at boot, every retry
  re-runs the read-only integrity scan before reaching the WSEP check. The scan is small at today's
  data volume.
- **The config keys keep their names,** including the generic `constants` prefix, so existing
  environments need no change. Renaming them is a separate, breaking change.

**Revisit when** the integrity scan becomes slow enough to matter on every self-heal tick, or a new
external dependency joins initialization: decide its row in the table above when it is added.

## Alternatives considered

- **Treat every initialization failure as fatal, including a WSEP outage.** Rejected: a cold or
  briefly unavailable WSEP endpoint would stop every deploy, against SLR.5 and SLR.6. The platform
  already recovers from it through `MarketSelfHealScheduler`.
- **Keep the WSEP check first and classify by exception type only.** Rejected: a WSEP outage at
  boot would still mask a missing admin or corrupt data, which the self-heal loop then swallows
  forever.
- **Validate configuration in one startup bean that reads every value.** Rejected: it duplicates
  the binding, keeps `@Value` strings scattered across modules, and gives a weaker error than Spring
  Boot's bind-validation report.
- **Make the state file fatal in every profile, `dev` included.** Rejected for now: a broken demo
  line would stop local development. The issue that asked for this decision (#27) scoped the
  default to profiles other than `dev`.
