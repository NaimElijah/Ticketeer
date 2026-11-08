# Architecture — module documentation

Spring Modulith–generated documentation of the platform's bounded-context modules.

The files here are a **hand-copied snapshot, last refreshed on 2026-07-09**, so they can lag the
code. `ModularityTests.GivenTheApplicationModules_WhenWriteModuleDocumentation_ThenTheComponentDiagramNamesEveryModule()` writes fresh output on every test run, but into
`target/spring-modulith-docs/`, not into this folder. To refresh the snapshot, copy the files over
from `target/`. Making the generator write here directly is tracked in #43.

## Files

| File | What it is |
|---|---|
| `components.puml` | C4 **component diagram** of all modules and their dependencies |
| `module-<name>.puml` | Per-module component diagram |
| `module-<name>.adoc` | Per-module **canvas** (spring beans, exposed types, dependencies) |
| `all-docs.adoc` | Aggregate AsciiDoc including every module canvas |

Render the `.puml` files with any PlantUML tool — the VS Code *PlantUML* extension,
<https://www.plantuml.com/plantuml>, or `plantuml *.puml`.

## The modules

Eleven modules, forming a **directed acyclic graph** (no two modules depend on each other, directly
or transitively):

- **shared** — the shared kernel: the `DomainException` hierarchy, base `IRepository`/`InvariantChecked`
  contracts, cross-cutting technical infra (AOP aspects, `RepositoryLocks`, `WsepHttpClient`,
  security config, metrics), the published-language DTOs (`shared/dto`), the cross-context
  purchase-policy domain (`shared/domain/policy`), and integration events (`shared/event`).
- **identity** — User, Session, Admin, authentication. The most foundational context.
- **organization** — ProductionCompany and the `CompanyAppointment` aggregate (company membership,
  roles, permissions) with its own repository.
- **catalog** — Event, venue maps, zones, seats, inventory.
- **sales** — ActiveOrder, OrderReceipt, Ticket, checkout, reservations, refunds.
- **messaging** — conversations and support inquiries.
- **notifications** — a pure sink: it consumes `shared/event` integration events (via an
  `@Async` `@TransactionalEventListener` adapter that runs after the publisher commits, on the
  `notify-` thread; [ADR-004](../ADRs/ADR-004-no-outbox-for-notifications.md)) and depends on nothing but the kernel.
- **governance** — platform lifecycle, market state, system analytics and integrity. A top-level
  consumer (only `ui` and `bootstrap` depend on it; the market gate is inverted through a
  sales-owned port).
- **reporting** — the read/reporting (CQRS query) side: cross-context read services (member purchase
  history, company dashboards). A top-level consumer used only by the UI.
- **ui** — the single Vaadin driving adapter (views, presenters). No backend module depends on it.
- **bootstrap** — the application composition root: lifecycle runners, `@Scheduled` sweepers, and
  the dev seed/`.scenario` engine. A pure sink that wires the contexts together.

Each backend context follows the canonical hexagonal layout
`domain / application(port.in, port.out, service) / adapter(out.*)`. The seams between contexts are
ports and events (e.g. `MarketGate`, `CompanyEventStatsPort`, `CartRestorationPort`,
`EventSalesHistoryPort`, `EventCancelledEvent`, the `shared/event` notices); other cross-context
calls still use services, repository ports and entities, and #54 replaces them.

## Enforcement status

Both gates run on every build and are **green**:

- **ArchUnit hexagonal, direction and acyclicity rules** (`HexagonalRulesTest`) — **enforced**:
  - the domain is free of Spring, Vaadin and the outer layers. Of the persistence stack it may use only
    `jakarta.persistence`, `org.hibernate.annotations`, and Jackson inside JPA `AttributeConverter`
    classes;
  - each context's domain depends only on its own context and `shared`;
  - the application layer never depends on adapters;
  - no backend context, `reporting` included, depends on `ui`;
  - `ui` never depends on another module's `application.port.out` or `adapter` packages. That is a
    deny-list: other modules' `domain` types and `config` records stay open to it (#208);
  - `ui` formats dates and numbers, and changes case, only in a locale it names: `DisplayLocale.PAGE`
    for text a page shows, `Locale.ROOT` for machine text
    ([ADR-027](../ADRs/ADR-027-pages-format-in-a-fixed-locale.md));
  - every `@Route` class is in `ui`, since Vaadin routes only `vaadin.allowed-packages` and silently
    skips a view outside it;
  - every listener of a `shared/event` notice is an `AFTER_COMMIT` `@TransactionalEventListener` with
    `fallbackExecution`, on `@Async("notificationExecutor")`
    ([ADR-004](../ADRs/ADR-004-no-outbox-for-notifications.md));
  - production code reads time only from the `Clock` bean
    ([ADR-008](../ADRs/ADR-008-event-time-zones-and-one-clock.md),
    [ADR-022](../ADRs/ADR-022-system-current-time-millis-is-a-system-clock-read.md)), and tests read it
    from a clock, never the JVM's ([ADR-024](../ADRs/ADR-024-tests-read-time-from-a-clock-never-the-jvm.md));
  - each module depends only on the modules its entry in the test's module map allows, plus the one
    `identity → notifications` class pair (#53). The graph is drawn in the Architecture section of
    the root [README](../../README.md) and described in
    [`.claude/CLAUDE.md`](../../.claude/CLAUDE.md); the reasoning is in
    [ADR-011](../ADRs/ADR-011-archunit-module-map-as-the-boundary-gate.md);
  - every allowed cross-module exception still matches a production dependency, so a dependency that
    has gone cannot come back unnoticed;
  - **`GivenProductionCode_WhenScanned_ThenBoundedContextsAreAcyclic()`** proves the module graph is a DAG, via ArchUnit slices. That is
    the same cycle engine Spring Modulith uses, run *without* the open-module exemption, so it is the
    substantive cycle guarantee.
- **Spring Modulith `verify()`** (`ModularityTests.GivenTheOpenApplicationModules_WhenVerifyModuleBoundaries_ThenReportsNoViolations`) — **enabled** and green, but it
  checks little: every module is declared *open*, an open module exposes all its types, and Modulith's
  cycle check leaves open modules out, so the rules above are the gate. Full type-level encapsulation (closed modules exposing only named-interface ports) is
  documented future work — this former layered monolith crosses boundaries through services and
  domain types, not only ports, so port-only encapsulation is a larger follow-on effort.
