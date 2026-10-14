# Requirements

What Ticketeer must do. How it does it is in [`docs/architecture/`](../architecture/README.md);
why a solution was chosen goes in `docs/ADRs/`. This document states intent: where code behaves in
a way no source document asks for, the behaviour is listed under [Open questions](#open-questions)
rather than promoted to a requirement.

## Purpose and scope

Ticketeer is an event-management and ticket-sales platform.

- **Production companies** (a Founder, Owners and Managers) publish events with venue maps and
  inventory, and set purchase and discount policies.
- **Guests and members** browse events, hold tickets for a limited time, and buy them in an
  all-or-nothing checkout against external payment and ticket-issuing services.
- **System admins** initialize the platform, open and close the market, and oversee purchase
  history, complaints and analytics.

The platform started as the Ben-Gurion University Software Engineering Workshop 2026 course
project, delivered in versions V0–V3. This repository continues it towards a production launch,
planned as milestones M1–M7 on the project board.

In scope: every requirement below whose status is Built, Partial or Deferred. Out of scope: see
[Non-goals](#non-goals).

### Sources

The course documents are the source of intent. They are the university's material and this
repository is public, so they are not committed; keep local copies under `docs/requirements/`.

| File | Content |
|---|---|
| `General_requirements_document.pdf` | Master specification: correctness constraints, external integrations, integrity rules, functional requirements I and II, service-level requirements 1–8, glossary. |
| `version_0.pdf` … `Version_3.pdf` | What each course version had to deliver. V2 adds II.6.7–II.6.9 and service level 3.5. V3 adds persistence, resilience, configuration and initial-state files, and the real external systems. |
| `WSEP_External_Systems.pdf` | HTTP API of the course's external payment and ticket-issuing service. |

Where the course documents contradict each other or leave a gap, the item is listed under
[Open questions](#open-questions).

### Identifiers

- **`I.n`, `II.x.y`** — course numbering for system (I) and user (II) requirements. This document
  is keyed on them.
- **`SLR.n`** — the general document's service-level requirements 1–8; `SLR.8.2` is item 8.2.
  Code citing "V3 Req 5/6/7" means SLR.5/6/7 as restated in Version 3.
- **`UC-n`** — the team's use-case book, kept outside this repository. Code and Javadoc cite these
  most often; the tables below map them to course IDs.
- **Use-case sub-IDs** such as `II.3.0.1`, `II.4.8.3` and `II.6.3.2` come from the same book. A few
  drift from the course numbering, and this document uses the course ID:

  | Code says | Course ID | Meaning |
  |---|---|---|
  | `II.1.4` | II.1.3 | Register |
  | `II.1.5` | II.1.4 | Log in |
  | `II.3.0.x` | II.3 (unnumbered "exit" item) | A member's order survives a disconnect |
  | `II.3.10` | II.3.7 | Send an inquiry to a company |

- **`§1 #n`, `§2 #n`, `§3 #n`** — the [business-rule](#business-rules-and-invariants) numbering,
  cited by `SystemIntegrityVerifier`.
- **`#n`** — in this document, an issue in this repository. In code and Javadoc, a bare `#n`
  usually means a ticket in the upstream course repository, `AdamSimkinbgu/event-ticket-system`,
  and so do codes like `V3-INIT-02`. A few numbers collide with issues here (`RefundService`
  cites upstream #64). Disambiguating them is #57.

### Status legend

| Status | Meaning |
|---|---|
| **Built** | Implemented and exercised by the named test. |
| **Partial** | Part is built; the gap is named and linked. |
| **Deferred** | Not built; planned in the linked issue. |
| **Non-goal** | Deliberately not built. |
| **TODO** | Undecided; see [Open questions](#open-questions). |

## Functional requirements

### I. System

| ID | UC | Requirement | Status | Enforced by · tested by |
|---|---|---|---|---|
| I.1 | UC-1 | Initialize the platform into a valid state: payment and ticket-issuing services connected (§2), at least one system admin (§1 #2). V3: parameters come from external configuration files, and an initial-state file may be replayed. | Partial: invalid initialization, including a failing initial-state step, does not stop startup (#27); destructive seed modes are not refused outside `dev` (#78) | `SystemAdminService.initializePlatform`, `PlatformInitializationRunner`, `SystemIntegrityVerifier` · `SystemAdminServiceTest`, `PlatformInitializationRunnerTest`, `SystemIntegrityVerifierTest` |
| I.2 | UC-32 | Open the market only in a valid state; reserving and checking out need an open market. | Built; an admin's CLOSE is lost on restart (#24) | `SystemAdminService.openMarket`, `SystemAdminService.closeMarket`, `MarketGate`, `MarketSelfHealScheduler` · `SystemAdminServiceTest`, `MarketSelfHealSchedulerTest` |
| I.3 | UC-33, UC-4 | Charge through an external payment service, with support for more than one. Refund automatically when ticket issuing fails or an event is cancelled. | Partial: one payment adapter (Stripe: #98); cancellation refunds are neither atomic nor resumable (#20) | `PaymentGateway`, `WsepPaymentGateway`, `CheckoutService`, `EventCancellationRefundListener` · `WsepPaymentGatewayTest`, `CheckoutServiceTest`, `EventCancellationRefundListenerTest` |
| I.4 | UC-34 | Issue tickets through an external service, with support for more than one. | Partial: one issuing adapter (in-house issuing: #100) | `TicketIssuer`, `WsepTicketIssuer` · `WsepTicketIssuerTest` |
| I.5 | UC-35 | Real-time alerts. Producers: sold out, company closed, role changes. Buyers: purchase completed, event cancelled or moved, order about to expire. All members: messages from admins or producers. | Partial: delivered by polling, not push (#61); several declared types are never sent (#63) | `NotificationDispatchService` · `NotificationDispatchServiceTest` |
| I.6 | UC-36, UC-37 | Delayed alerts: keep alerts for offline members and show them at their next login. | Built | `NotificationDispatchService.dispatch`, `NotificationDispatchService.deliverPending` · `NotificationDispatchServiceTest`, `DelayedAppointmentNotificationAcceptanceTest` |
| I.7 | — | Load management: above a load threshold, for the whole system or for one event, send visitors to a virtual waiting room and admit them in batches. | Deferred (#71) | — |

### II.1 Guest visitor

| ID | UC | Requirement | Status | Enforced by · tested by |
|---|---|---|---|---|
| II.1.1 | — | Enter as a guest. Choosing the first tickets opens an active order that holds them for a limited time. | Built | `ReservationService` · `ReservationServiceTest` |
| II.1.2 | UC-2 | Exit. A guest's active order belongs to that visit only; its tickets are released when the hold expires. | Built | `SessionAndOrderSweeper` · `SessionAndOrderSweeperTest` |
| II.1.3 | UC-11 | Register as a member. The current visit stays a guest visit until login. | Built | `AuthenticationService.register` · `AuthenticationServiceTest`, `AuthAcceptanceTest` |
| II.1.4 | UC-12 | Log in as a member. | Built; the valid-login acceptance test is disabled (#51) | `AuthenticationService.login`, `JwtSessionManager` · `AuthenticationServiceTest`, `JwtSessionManagerTest` |

### II.2 Purchasing as a guest (members can do all of this too)

| ID | UC | Requirement | Status | Enforced by · tested by |
|---|---|---|---|---|
| II.2.1 | UC-3 | Get information on active production companies and their events. | Built | `CatalogService.browseEventCatalog` · `CatalogServiceTest` |
| II.2.2 | UC-8 | See the event map and live inventory: overall availability for standing zones, free or taken for each seat. | Built | `CatalogService.getEventVenueMap` · `CatalogServiceTest`, `VenueMapTest` |
| II.2.3.a | UC-7 | Search all events by name, artist, category or keyword; filter by price, date, location, and event or company rating. | Built | `CatalogService`, `EventSearchSpecification` · `CatalogServiceTest`, `SearchPresenterTest` |
| II.2.3.b | UC-7 | Search within one company, with the same filters except company rating. | Partial: built in the backend, not offered in the UI (#69) | `CatalogService` · `CatalogServiceTest` |
| II.2.4 | UC-5, UC-9 | Reserve: tickets added to the active order are locked for the fixed hold, and nobody else can buy them meanwhile. | Built | `ReservationService`, `InventoryService` · `ReservationServiceTest`, `ReservationConcurrencyTest` |
| II.2.5.a | UC-5 | Choose specific numbered seats. | Built | `ReservationService` · `SeatedReservationJpaTest` |
| II.2.5.b | UC-9 | Choose a number of tickets in a standing zone. | Built | `InventoryService.reserve` · `ReservationServiceTest` |
| II.2.6 | — | Wait in a queue under extreme load, see a position or estimated wait, and get time-boxed access when admitted. | Deferred (#71) | — |
| II.2.7 | — | View and change the active order (remove tickets, change quantities) until it expires. | Partial: viewing and removing are built; changing a quantity is #64 | `ReservationService` · `ReservationServiceTest` |
| II.2.8 | UC-10 | Check out under the company's purchase and discount policies before the hold expires. All or nothing: a payment or issuing failure, or an expired hold, buys nothing and releases the tickets. | Built; money is a `double` (#73) | `CheckoutService` · `CheckoutServiceTest`, `CheckoutServiceAcceptanceTest` |

### II.3 Purchasing as a member

| ID | UC | Requirement | Status | Enforced by · tested by |
|---|---|---|---|---|
| II.3 (exit) | UC-13 | A member's active order survives a disconnect: logging in again before the hold expires resumes it; after expiry it is cancelled and the tickets released. | Built | `CartRestorationPort`, `CartRestorationAdapter`, `SessionAndOrderSweeper` · `CartRestorationAdapterTest`, `SessionAndOrderSweeperTest` |
| II.3.1 | UC-14 | Log out; the visit becomes a guest visit. | Built | `AuthenticationService.logout` · `SignOutFlowTest`, `AuthAcceptanceTest` |
| II.3.2 | UC-18 | Open a production company and become its Founder and first Owner. | Built | `CompanyManagementService.registerCompany` · `CompanyManagementServiceTest` |
| II.3.3 | — | File a complaint with the system admins (scalping, fraud, terms violations). | Built | `MessagingService` · `MessagingServiceTest`, `MessagingAcceptanceTest` |
| II.3.4 | UC-15 | View and change identifying details. | Partial: viewing is built; editing the profile and changing the password are #60 | `MyProfileView` |
| II.3.5 | UC-16 | See the personal purchase history. It does not change when events, prices or companies change later. | Built | `MemberAccountService.viewMyHistory`, `OrderReceipt` · `MemberAccountServiceTest` |
| II.3.6 | — | Register for a purchase-rights lottery on high-demand events. | Deferred (#72) | — |
| II.3.7 | — | Send an inquiry to a production company. | Built | `MessagingService.startConversation` · `MessagingServiceTest` |

### II.4 Owner or Founder

| ID | UC | Requirement | Status | Enforced by · tested by |
|---|---|---|---|---|
| II.4.1 | UC-19 | Manage events and inventory: add, edit and remove events; show dates; seated zones with rows and seats; standing-zone capacities. | Built | `EventManagementService`, `Event` · `EventManagementServiceTest`, `EventTest` |
| II.4.2 | UC-20 | Configure the venue map; every map zone links to an inventory zone, and the map shows free and taken places live. | Built | `EventManagementService.configureVenueMap`, `VenueMap` · `EventManagementServiceTest`, `VenueMapTest`, `InventoryZoneTest` |
| II.4.3 | UC-21 | Set purchase methods and purchase and discount policies. | Partial: purchase policies are built; discounts are one percentage per event; conditional, coupon and composed discounts are #67; the lottery purchase method is #72 | `EventManagementService.setEventPolicies`, `PurchasePolicy`, `DiscountPolicy` · `PurchasePolicyAcceptanceTest`, `PurchasePolicyEnforcementAcceptanceTest` |
| II.4.4 | — | Receive buyer inquiries and answer them. | Built | `MessagingService` · `MessagingAcceptanceTest` |
| II.4.5 | UC-22 | See the company's order and purchase history. It does not change when events, prices or companies change later. | Built | `CompanyAnalyticsService.salesHistory`, `OrderReceiptMapper.toCompanySalesRecordDTO` · `CompanyAnalyticsServiceTest`, `CompanyAcceptanceTest` |
| II.4.6 | UC-22 | Produce a sales report of revenue and tickets sold, limited to the events the owner manages and their appointment subtree. | Partial: company-wide figures, not limited to the subtree (#68) | `CompanyAnalyticsService.dashboard` · `CompanyAnalyticsServiceTest`, `CompanySalesReportAcceptanceTest` |
| II.4.7 | UC-24 | Appoint a manager with chosen permissions; the appointee must accept. | Built; the permission set is [OQ-2](#open-questions) | `CompanyManagementService.appointManager`, `CompanyMembershipService` · `ManagerPermissionMatrixAcceptanceTest`, `CompanyMembershipServiceLifecycleTest` |
| II.4.8 | UC-23 | Appoint a co-owner; the appointee accepts or rejects. | Built | `CompanyManagementService.appointOwner`, `CompanyMembershipService.receiveOwnerAppointment` · `CompanyMembershipServiceTest`, `DelayedAppointmentNotificationAcceptanceTest` |
| II.4.9 | — | Remove an owner you appointed. | Partial: the service revokes owners but does not cascade to their appointees, and no UI offers it (#65, #126) | `CompanyManagementService.RevokeAppointment` |
| II.4.10 | — | Give up ownership. The Founder cannot. | Partial: an owner can revoke their own appointment through the same service call, with the same gaps (#65, #126) | `CompanyManagementService.RevokeAppointment` |
| II.4.11 | UC-24 | Change the permissions of a manager you appointed. | Built | `CompanyMembershipService` · `CompanyMembershipServiceLifecycleTest` |
| II.4.12 | UC-24 | Remove a manager you appointed. | Built | `CompanyAppointment.revoke` · `CompanyMembershipServiceLifecycleTest` |
| II.4.13 | — | The Founder suspends or closes the company. | Deferred (#62) | — |
| II.4.14 | — | The Founder reopens a closed company. | Deferred (#62) | — |
| II.4.15 | UC-25 | See the company's role tree and each manager's permissions. | Built | `CompanyManagementService.viewOrganizationalTree` · `CompanyManagementServiceTest`, `OrgTreePresenterTest` |

### II.5 Manager

| ID | UC | Requirement | Status | Enforced by · tested by |
|---|---|---|---|---|
| II.5 | UC-24 | Perform management actions within the permissions the appointing owner granted. | Partial: enforced by the services; the UI's company selector and capability resolver are mocks (#59) | `CompanyMembershipService` · `ManagerPermissionMatrixAcceptanceTest` |

### II.6 System admin

| ID | UC | Requirement | Status | Enforced by · tested by |
|---|---|---|---|---|
| II.6.1 | — | Close a production company. Its owners and managers are alerted and their appointments cancelled. | Deferred (#62) | — |
| II.6.2 | — | Cancel a membership, which cancels all of the member's roles in every company. | Deferred (#66) | — |
| II.6.3 | — | Handle complaints and reply; send system messages to producers and buyers. | Built | `MessagingService.sendOutreach` · `MessagingServiceTest` |
| II.6.4 | UC-31 | See the global purchase history, by buyer or by company and event. | Built | `SystemAdminService.viewGlobalHistory` · `SystemAdminServiceTest` |
| II.6.5 | UC-46 | See live and historical analytics: visits, registrations, reservations and purchases. | Built | `SystemAnalyticsService` · `SystemAnalyticsServiceTest` |
| II.6.6 | — | See all active queues live, change the admission rate, and clear a queue after a fault. | Deferred (#71) | — |
| II.6.7 | — | Suspend a user for a period or permanently; a suspended user can only view (V2). | Deferred (#66) | — |
| II.6.8 | — | Lift a suspension (V2). | Deferred (#66) | — |
| II.6.9 | — | See suspensions with their start, duration and end (V2). | Deferred (#66) | — |

## Business rules and invariants

### §1 Correctness constraints

The course lists these as unnumbered bullets. The numbers below are the ones the code already
cites; "a buyer has an active order" and "at most one per event" are one item (§1 #8).

| # | Rule | Enforced by · tested by |
|---|---|---|
| 1 | A member's username is unique. | `AuthenticationService.register`; scanned by `SystemIntegrityVerifier` · `AuthenticationServiceTest`, `SystemIntegrityVerifierTest` |
| 2 | At least one system admin exists. | `SystemAdminService.initializePlatform` · `SystemAdminServiceTest` |
| 3 | Every system admin is a member. | **Not upheld.** Admins are a separate pool, not members (`SystemIntegrityVerifier`). The deviation still needs its ADR (#44). |
| 4 | Company owners and managers are members. | `CompanyManagementService.appointOwner`, `SystemIntegrityVerifier` · `SystemIntegrityVerifierTest` |
| 5 | Only users who are currently visiting can act. | A valid session token on every write (`SessionManager`) · `JwtSessionManagerTest` |
| 6 | An active company has at least one owner. | The Founder can be neither removed nor resign (`ProductionCompany`, `CompanyManagementService.RevokeAppointment`; no test covers these guards); scanned by `SystemIntegrityVerifier` · `SystemIntegrityVerifierTest` |
| 7 | Every company and event has purchase and discount policies, defaulting to "no restrictions". | By construction: `NoPurchasePolicy` is the default |
| 8 | A buyer has one active order collecting their tickets, and at most one per event at any time. | Saving a second cart for the same buyer replaces the first (`ActiveOrderRepository`); scanned by `SystemIntegrityVerifier` · `IActiveOrderRepositoryContractTest`, `SystemIntegrityVerifierTest` |
| 9 | Adding a ticket to an active order locks it for a fixed time (for example 10 minutes). | `ReservationHoldConfig` reads `constants.ticket-reservation-duration` · `ReservationServiceTest` |
| 10 | An order not completed within that time is cancelled and its tickets return to stock. | `SessionAndOrderSweeper` · `SessionAndOrderSweeperTest` |
| 11 | An active order belongs to its buyer; nobody else can change it before it expires. | `ReservationService`, `CheckoutService` · `ReservationServiceTest` |
| 12 | An event never sells more tickets than its inventory holds. | `StandingZone`, `SeatedZone`, `Ticket.reserve`, optimistic locking; scanned by `SystemIntegrityVerifier` · `ReservationConcurrencyTest`, `SystemIntegrityVerifierTest` |

### §2 External integrations

| # | Rule | Enforced by · tested by |
|---|---|---|
| 1 | At least one payment service is connected. | The initialization quorum in `SystemAdminService.initializePlatform` · `SystemAdminServiceTest` |
| 2 | At least one ticket-issuing service is connected. | Same as §2 #1 |

### §3 Commercial integrity rules

The course states §3 #3 and §3 #4 as one bullet; the code cites them separately.

| # | Rule | Enforced by · tested by |
|---|---|---|
| 1 | Charge buyers only for completed transactions, and only the declared amounts. | `CheckoutService.validatePaymentResult` rejects a charge whose amount or currency differs from the request · no test covers the mismatch branch |
| 2 | A company receives money only from a successful purchase. | `CheckoutService` · `CheckoutServiceTest` |
| 3 | A purchase succeeds if and only if both the payment and the ticket-issuing services approve it. | `CheckoutService` · `CheckoutServiceTest`, `CheckoutServiceAcceptanceTest` |
| 4 | When payment succeeds but issuing fails, refund automatically and tell the buyer. | `CheckoutService` refunds and publishes `PurchaseFailedNotice` to members. Guests have no channel to be told through until #102. |
| 5 | Obey the law: issue receipts, prevent illegal resale, never sell 18+ events to minors. | Receipts: `OrderReceipt`. Age: `AgePurchasePolicy`. Resale: only a per-buyer limit (`MaxTicketsPurchasePolicy`); see [OQ-10](#open-questions). |

### Roles and appointments

- A user holds at most one role per company. `CompanyMembershipService` allows one active or
  pending appointment per user per company.
- Any owner may appoint, as an owner, a member who is not yet an owner of the company (II.4.8),
  including one of its managers. The code accepts such an offer from any owner, but lets the
  manager accept it only when it comes from their own appointer
  (`CompanyMembershipService.acceptInvitation`); that
  restriction is [OQ-8](#open-questions).
- Opening a company makes the opener its Founder and first owner
  (`CompanyMembershipService.addFounderAppointment`).
- Appointees accept or reject (`CompanyMembershipService.acceptInvitation`,
  `CompanyMembershipService.rejectInvitation`).
- Each owner has exactly one appointer, the Founder has none, and appointments form a tree, never
  a cycle (II.4.8). **Not enforced.**
  - The appointer must be an owner (`CompanyMembershipService.requireOwnerInCompany`), and an
    active owner cannot be appointed again (`CompanyMembershipService.receiveOwnerAppointment`).
  - But `CompanyManagementService.RevokeAppointment` revokes owners too, with no cascade, and a
    revoked owner can be re-appointed by their own appointee. That forms a cycle (#126). No UI
    reaches owner revocation today.
  - `AppointmentCycleException` is never thrown, and the acceptance test for cycles is disabled
    (#52).
- Each manager has exactly one appointer, an owner, and only that appointer may change or remove
  the manager (`CompanyAppointment.revoke`, `CompanyMembershipService`). Editing by another owner
  is rejected in `CompanyAcceptanceTest`; removal by another owner has no test.
- The Founder can neither resign nor be removed (`ProductionCompany`).

### Company lifecycle (Deferred, #62 and #66)

- Only the Founder suspends, closes or reopens a company, and that keeps its appointments. An
  admin closing a company cancels them.
- An inactive company's events leave search results and cannot be bought.
- Cancelling a membership cancels all of that member's roles. A suspended user can only view.

### Purchase flow

- A hold lasts `constants.ticket-reservation-duration` minutes. Expired holds return to stock
  (`SessionAndOrderSweeper`).
- Checkout is all or nothing. Any failure releases the tickets and refunds a charge that went
  through (`CheckoutService`).
- A guest's order ends with the visit; a member's lasts until the hold expires.
- Receipts and purchase history never change when events, prices or companies change later
  (II.3.5, II.4.5). Receipts store the price at purchase (`OrderReceipt`, `ReceiptLine`).
- A company's sales history shows only its own tickets and the revenue from them. An order's
  payment transactions cover the whole order, which may hold other companies' tickets, so no
  company sees them; only the admin global history (II.6.4) does (#121;
  `OrderReceiptMapper.toCompanySalesRecordDTO` · `CompanyAnalyticsServiceTest`, `CompanyAcceptanceTest`).

### Purchase policies and discounts

- A purchase policy is a condition on the buyer's age and the number of tickets (minimum or
  maximum). Conditions combine with AND and OR to any depth, and a policy is set per company, per
  event, or both (`PurchasePolicy`, `AndPurchasePolicy`, `OrPurchasePolicy`, `AgePurchasePolicy`,
  `MinTicketsPurchasePolicy`, `MaxTicketsPurchasePolicy` · `PurchasePolicyAcceptanceTest`).
- Discounts (V2) are percentages. There are three kinds:
  - simple;
  - conditional, which applies when a condition holds;
  - coupon, which applies when the buyer enters a code at checkout within its validity.

  Discounts combine by **maximum** (no double discounts) or **sum**, to any depth. Only one
  percentage per event is built (`DiscountPolicy`); the rest is #67.
- When a policy refuses a purchase, the UI explains why, for example "Tickets for this event can
  be bought from age 18" (V2, service level 3.5).

### Lottery and queue (Deferred, #71 and #72)

- **Lottery.** It applies only where the company enables it. Members register in advance, and only
  winners get an alert and a unique, time-limited code to reserve when sales open.
- **Queue.** It starts above a load threshold, set for the whole system or per event
  ([OQ-7](#open-questions)), and admits visitors in batches, with time-boxed access once admitted.
  Admins can change the rate or clear a queue. Queue state is never persisted (V3).

## Non-functional requirements

| ID | Requirement | Status | Enforced by · tested by |
|---|---|---|---|
| SLR.1 | Consistency and concurrency: every action keeps the platform valid; reserving and purchasing are atomic; no seat is ever sold twice. | Partial: event-cancellation refunds can stop halfway (#20) | Optimistic locking, `OptimisticLockTranslationAspect` · `ReservationConcurrencyTest`, `RefundConcurrencyTest`, `OptimisticLockTranslationAspectTest` |
| SLR.2 | Privacy and security: identifying data is neither exposed nor recoverable; passwords are stored hashed; requests after login carry a token; passwords are masked in the UI. | Partial: session-id rotation #75, default secrets #34, admin credentials #77 | `BcryptPasswordHasher`, `JwtSessionManager` · `AuthenticationServiceTest`, `JwtSessionManagerTest` |
| SLR.3 | User experience: 3.1 response time holds under load; 3.2 help with common errors; 3.3 a convenient UI; 3.4 each role sees only the actions it may perform; 3.5 the UI explains successes and failures (V2). | Partial: 3.4 depends on a mock capability resolver (#59); accessibility #110; for 3.3, browse filters kept in the URL #70 | `AuthBootstrap`, `RequireCapability`, `Capability` |
| SLR.4 | Load, stress and availability: unlimited visitors, companies and events; spikes when sales open; continuous availability. | TODO: no targets set ([OQ-11](#open-questions)). Load test #108; waiting queue #71 | — |
| SLR.5 | Robustness: keep working under load, network failures and the loss of an external service. | Partial: external-call timeouts and handshake retries are built; cancellation refunds cannot resume (#20) | `WsepHttpClient` · `WsepHttpClientRetryTest` |
| SLR.6 | Recovery: return to normal operation after a fault, for example resume automatically when the database comes back. | Built; not run in CI (#32) | Connection-pool settings in `application-supabase.yml`, `MarketSelfHealScheduler` · `DbOutageRecoveryLocalPgIT`, `MarketSelfHealSchedulerTest` |
| SLR.7 | Persistence: state survives a planned or unplanned shutdown. V3: a remote database is supported, and switching databases is a configuration change only. | Partial: built under the `jpa` profile; no schema migrations (#31); market state (#24), idempotency keys (#25) and login lockouts (#39) are held in memory | The JPA repository adapters and their contract tests · `TicketIdRestartJpaTest` |
| SLR.8 | Tracking: 8.1 operational measurements; 8.2 error tracking, preferably in real time. V1 adds an event log and an error log. | Partial: logs exist; correlation ids and JSON logs #30; metrics and traces #37 | `LoggingAspect`, `logback-spring.xml` |

### Course delivery requirements (V2, V3)

| ID | Requirement | Status | Enforced by · tested by |
|---|---|---|---|
| V2 3.a | Report test coverage automatically, for example with JaCoCo. | Deferred (#45); exempted under V2 Tier B in the course project | — |
| V3 2.a.i | Initialize from external configuration files; database connection details live in configuration, not code. | Partial: every value in `application.yml` can be overridden from the environment; values are not validated (#27) | `application.yml` |
| V3 2.a.ii | Optionally initialize from an initial-state file that replays use cases through the application layer. If any step fails, initialization fails. | Partial: the `.scenario` engine runs in every profile except `test` when `seed.mode` is set, but a failing step does not stop startup by default (#27) | `ScenarioRunner`, `ScenarioOps` |
| V3 2.b | Connect to the real external payment and ticket-issuing systems. | Built (WSEP); Stripe replaces WSEP payments in #98 | `WsepPaymentGateway`, `WsepTicketIssuer` · `WsepPaymentGatewayTest`, `WsepTicketIssuerTest` |
| V3 4.a | Resilience tests cover the external systems and the database. | Built | `WsepHttpClientRetryTest`, `DbOutageRecoveryLocalPgIT` |
| V3 4.b | Tests prove the system does not start after an invalid initialization. | Deferred (#27) | — |
| V3 4.c | Tests use a dedicated configuration and never reach the real external systems or the production database. | Built: the `test` profile and `application-test.yml` swap in stub gateways | `application-test.yml` · every test that runs under the `test` profile |
| V3 appendix 3 | Use the ORM's built-in cache. | TODO: [OQ-12](#open-questions), evaluated in #33 | — |
| V3 appendix 4 | Each use case runs as one transaction. | Partial: most application services are one `@Transactional` method; checkout and refunds deliberately call the external services outside the transaction and commit afterwards (ADR pending, #44); cancellation refunds are not atomic (#20) | `CheckoutService`, `RefundService` |

## Non-goals

- **Committing the course documents.** They are the university's material and this repository is
  public.
- **Persisting waiting-queue state.** Queues are transient (V3).
- **Designing the database independently of the domain.** The ORM maps the domain model onto
  tables, never the reverse (V3).
- No other non-goals have been decided. Propose one under [Open questions](#open-questions).

## Open questions

Every question is owned by @NaimElijah, the project owner.

| # | Question | Where it shows up |
|---|---|---|
| OQ-1 | **Company rating.** The course makes event and company rating search filters, but no use case creates a rating. The code derives a company's rating from its events' ratings (`CompanyRatings`). Who rates events, and how? | `CompanySummaryDTO` |
| OQ-2 | **Manager permissions.** II.4.7 lists six: inventory, venue map, policies, inquiries, purchase history, sales report. The code has five, and `VIEW_SALES` covers both purchase history and the sales report. Should it be split? | `Permission` |
| OQ-3 | **Coupons and composed discounts** (#67). V2 says discounts are percentages; the general document also allows fixed-amount coupons. Which? | `DiscountPolicyDTO` |
| OQ-4 | **Notification types** (#63). Which of the I.5 alerts must be sent, and to whom? | `NotificationType` |
| OQ-5 | **Contradictions in the course documents.** V2 calls the alert requirements "I.4 and I.5", where the general document has I.5 and I.6; I.1 and I.2 nearly duplicate each other. This document follows the general document. Confirm. | — |
| OQ-6 | **Buyer age.** Age policies need it, but guests have no stored age. The code defers the check to checkout and refuses an unknown age (`AgePurchasePolicy`). Is that the rule? | `AgePurchasePolicy` |
| OQ-7 | **Lottery and queue parameters** (#71, #72): draw method, number of winners, code lifetime; a per-event or system-wide threshold; admission rate. | — |
| OQ-8 | **Rules found only in code.** Confirm or change them: a password has at least 8 characters with a letter and a digit; 5 failed logins lock the account for 15 minutes; a member session lasts 24 hours, a guest session ends after 30 idle minutes; usernames are 3–32 characters, checked only in the UI (#76); company and event purchase policies both apply; a member's refund covers the whole order; a manager can be promoted to owner only by their own appointer. | `AuthenticationService`, `application.yml` |
| OQ-9 | **Resolved (#121).** A company sees none of a shared order's payment transactions, only its own tickets and revenue; see [Purchase flow](#purchase-flow). | `OrderReceiptMapper.toCompanySalesRecordDTO` |
| OQ-10 | **Anti-scalping** (§3 #5). Is a per-buyer ticket limit enough, or are identity checks or resale rules required? | `MaxTicketsPurchasePolicy` |
| OQ-11 | **Load and availability targets** (SLR.4). The course asks for "unlimited" visitors and continuous availability, with no numbers. What peak concurrency, response time and availability must launch meet? Feeds #108. | — |
| OQ-12 | **ORM cache** (V3 appendix 3). Is Hibernate's second-level cache required for catalog reads? Evaluated in #33. | — |
