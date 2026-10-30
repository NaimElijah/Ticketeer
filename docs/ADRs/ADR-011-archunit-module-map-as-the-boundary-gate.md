# ADR-011: An ArchUnit module map is the boundary gate while the Modulith modules stay open

- **Status:** Accepted
- **Date:** 2026-10-30
- **Issue:** #184 (architecture conformance scan); related #53, #54, #55, #208

## Context

Every module under `com.ticketing.system` is a Spring Modulith `@ApplicationModule` of
`Type.OPEN`. Modulith's `verify()` checks the module model, but it does not check encapsulation
between open modules, so it stops no module from reaching into another one.

The code does reach across contexts. Besides the seams (ports and events), some contexts still use
another context's repository ports, entities and services; `.claude/CLAUDE.md` (Architecture) lists
them. #54 replaces those calls, #53 moves the one call into `notifications` behind an inbound port,
and #55 closes the modules.

`.claude/CLAUDE.md` documented a module graph, but `HexagonalRulesTest` enforced only that the
graph is acyclic (`bounded_contexts_are_acyclic`). An edge that closes no cycle, such as
`notifications` starting to read `organization`, passed. Scan #184 found that the documented graph
and the enforced one differed.

Forces:
- **Domain classes double as the JPA entities.** The `domain` packages therefore import
  `jakarta.persistence` and Hibernate's mapping annotations, and the JSON columns use JPA
  `AttributeConverter`s that sit next to their entities and use Jackson.
- **The `ui` is the one driving adapter.** It should use each context through its inbound
  application API (`ui/package-info.java`).
- **ArchUnit's field-based engine silently checked nothing** in this project's Surefire setup
  (recorded in the class Javadoc of `HexagonalRulesTest`). A rule that cannot fail is worse than no
  rule.

## Decision

**`HexagonalRulesTest` is the boundary gate while the modules stay open.** It enforces the
following, and `.claude/CLAUDE.md` (Architecture) and the root `README.md` state the same graph.

- **An explicit module map.** `ALLOWED_MODULE_DEPENDENCIES` names, for each top-level module, the
  modules it may depend on. A class in a module the map does not name fails the build, so a new
  module is a decision about the graph. The map has one class-pair exception:
  `identity.application.service.AuthenticationService` →
  `notifications.application.service.NotificationDispatchService` (#53). It is pinned to that
  pair, so it cannot spread to other `identity` classes or other `notifications` types.
- **Domain containment.** A context's `domain` package depends only on its own context and
  `shared`, and not on `shared.external` or `shared.persistence`.
- **Persistence allowances in the domain.** The domain may use `jakarta.persistence` and
  `org.hibernate.annotations`, but not Hibernate's runtime API. It may use Jackson only inside
  `AttributeConverter` classes and the classes nested in them, anonymous ones included.
- **A deny-list for the `ui`.** The `ui` never depends on another module's `application.port.out`
  or `adapter` packages. Another module's `domain` types and `config` records stay open to it for
  now (#208).
- **Self-tests for every rule.** Each rule has tests that run it against offenders under
  `architecture/fixture` and assert that it fails, and a test that the allowance stays as narrow as
  stated. The rules are built from a root-package parameter, so the same code runs against the
  fixtures and against production, because an earlier rule engine silently checked nothing.
- **A stale allowance fails.** A production test fails if the `AuthenticationService` →
  `NotificationDispatchService` allowance no longer matches a real dependency, so the exception
  leaves the map when #53 removes the call.

`ModularityTests` keeps Modulith's `verify()` and the Documenter output.

## Consequences

**What this makes easy:**
- A new cross-module edge is an explicit, reviewed change to the map and to `.claude/CLAUDE.md`
  together. The build fails until both agree.
- The documented graph and the enforced one cannot drift apart unnoticed.
- Every rule is proven to fail on an offender, so a rule that quietly stops matching is caught.

**What this makes hard, and what is accepted:**
- **The map and the docs are kept in step by hand.** Nothing generates one from the other.
- **It is not type-level encapsulation.** A module can still use any public type of another module
  that the map allows. Only the module-to-module direction, the domain and the `ui` deny-list are
  checked.
- **The `ui` rule is a deny-list.** A new package kind in a context is open to the `ui` until
  someone adds it to the list. The `domain` types and `config` records the `ui` uses stay open
  until #208.
- **The rules key on package names.** Moving a package, or the root package, changes what they
  match, which is why the fixtures mirror the module names.

**Revisit when:**
- #54 is done. The modules can then be closed (#55), and Modulith named interfaces replace the map
  and the `ui` deny-list;
- a new top-level module is proposed. It needs an entry in the map and in `.claude/CLAUDE.md`.

## Alternatives considered

- **Close the Modulith modules now, with named interfaces (#55).** It gives type-level
  encapsulation from a tool the project already uses. Rejected because every cross-context edge
  that #54 lists would fail at once, so the change would be the whole of #54 in one step. It is
  the destination named above.
- **ArchUnit `FreezingArchRule` over today's violations.** Rejected because the module edges
  already conform to the documented direction, so there is no violation to freeze. A frozen store would also move
  the documented graph into a generated file that nobody reads in review, instead of keeping it
  in one map beside the docs.
- **Acyclicity only, the status quo.** Rejected because it let undocumented edges through (scan
  #184), and `.claude/CLAUDE.md` claimed more than the build checked.
- **Move the JSON converters into adapters, so that the domain needs no Jackson rule.** Rejected
  because the entities' `@Convert` annotation would then depend on an adapter, which the domain
  rule forbids. The converters stay next to their entities and are the one place Jackson is
  allowed.
