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
- **System admins** open and close the market and oversee purchase history, complaints and
  analytics; the platform initializes itself at startup (I.1).

The platform started as the Ben-Gurion University Software Engineering Workshop 2026 course
project, delivered in versions V0–V3. This repository continues it towards a production launch,
planned as GitHub milestones M0–M8 (M0 audits the system; M8 adds ML and AI features, whose
requirements #134 will add).

In scope: every requirement below whose status is Built, Partial or Deferred; an entry marked TODO
stays undecided until its open question is answered. Out of scope: see
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
- **`Vn x.y`** — item x.y of course version n's requirements list (`version_0.pdf` …
  `Version_3.pdf`); `Vn impl x.y` is item x.y of its implementation-emphases list, and
  `Vn appendix n` its appendix. ADR-006 predates this scheme and writes `V3 4.c` for `V3 impl 4.c`.
- **`UC-n`** — the team's use-case book, kept outside this repository. Code and Javadoc cite these
  most often; the tables below map them to course IDs.
- **Use-case sub-IDs** such as `II.3.0.1`, `II.4.8.3` and `II.6.3.2` come from the same book. A few
  drift from the course numbering, and this document uses the course ID:

  | Code says | Course ID | Meaning |
  |---|---|---|
  | `I.1.1`–`I.1.4` | I.1 | UC-1 steps: invariants, payment service, ticket issuer, default admin |
  | `I.2.1`, `I.2.2` | I.2 | UC-32 steps: invariants, external services |
  | `II.1.4` | II.1.3 | Register |
  | `II.1.5` | II.1.4 | Log in |
  | `II.3.0.x` | II.3 (unnumbered "exit" item) | A member's order survives a disconnect |
  | `II.3.10` | II.3.7 | Send an inquiry to a company |
  | `I.5.1`, `I.5.2`, `I.5.3` | I.5.a, I.5.b, I.5.c | Alerts to producers, buyers and all members |
  | `II.6.3.1`, `II.6.3.2` | II.6.3.a, II.6.3.b | Complaint handling; proactive system messages |

  The letter suffixes in `I.5.a`–`I.5.c` and `II.6.3.a`–`II.6.3.b` are this document's splits of
  one course item into separately testable entries; the code numbers the same splits (table
  above). `II.2.1 (policies)` holds an obligation the general document states in its system
  description rather than in II.2.1 itself.

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
| **Not upheld** | A business rule the code breaks today; the reason and the issue are named. |
| **Not enforced** | A business rule nothing prevents breaking; the issue is named. |
| `TODO <TestName>` in an *Enforced by* cell | The requirement is decided, but the named test does not exist yet. The linked issue writes it; otherwise the test-suite scan (#189) does. |

## Functional requirements

### I. System

| ID | UC | Requirement | Status | Enforced by · tested by |
|---|---|---|---|---|
| I.1 | UC-1 | Initialize the platform into a valid state: payment and ticket-issuing services connected (§2), at least one system admin (§1 #2). V3: parameters come from external configuration files, and an initial-state file may be replayed. | Partial: invalid configuration, a failed platform initialization and, while `seed.fail-fast` is on (everywhere but `dev`), a failing initial-state step stop startup; a WSEP outage self-heals ([ADR-003](../ADRs/ADR-003-fatal-and-self-healing-init-failures.md)). Gaps: the initialized state breaks §1 #3, since admins are not members (#44); the queue-threshold parameter V3 names does not exist (#71). Separately, destructive seed modes are not refused outside `dev` (#78) | `SystemAdminService.initializePlatform`, `PlatformInitializationRunner`, `SystemIntegrityVerifier`, `ScenarioRunner` · `SystemAdminServiceTest`, `PlatformInitializationRunnerTest`, `SystemIntegrityVerifierTest`, `ScenarioRunnerTest` |
| I.2 | UC-32 | Open the market only in a valid state. While the market is not open, the system refuses reservations and checkouts. | Built; an admin's CLOSE is lost on restart, because `MarketOpener` reopens the market on every boot (#24) | `SystemAdminService.openMarket`, `SystemAdminService.closeMarket`, `MarketGate`, `MarketOpener`, `MarketSelfHealScheduler` · `SystemAdminServiceTest`, `MarketSelfHealSchedulerTest`, `ReservationServiceTest`, `CheckoutServiceTest` |
| I.3 | UC-33, UC-4 | Charge through an external payment service, with support for more than one. Refund automatically when ticket issuing fails or an event is cancelled. | Partial: only one payment adapter can be wired (`CheckoutService` takes a single `PaymentGateway`); #98 replaces WSEP with Stripe rather than adding a provider ([OQ-13](#open-questions)). WSEP can only reverse a whole charge, so refunding one event of a multi-event order over-refunds at the payment service (#98, [ADR-001](../ADRs/ADR-001-refund-state-per-receipt-line.md)) | `PaymentGateway`, `WsepPaymentGateway`, `CheckoutService`, `EventCancellationRefundService` · `WsepPaymentGatewayTest`, `CheckoutServiceTest`, `EventCancellationRefundServiceTest`, `EventCancellationAcceptanceTest` |
| I.4 | UC-34 | Issue tickets through an external service, with support for more than one. | Partial: only one issuer can be wired (`CheckoutService` takes a single `TicketIssuer`; [OQ-13](#open-questions)). WSEP receives seated tickets as general admission, and issued tickets are never cancelled after a refund or a failed checkout ([OQ-14](#open-questions)); in-house issuing replaces WSEP in #100 | `TicketIssuer`, `WsepTicketIssuer` · `WsepTicketIssuerTest` |
| I.5.a | UC-35 | Alert producers in real time when an event sells out, their company is closed or suspended, or their role changes. | Partial: delivered by polling, not push (#61); sold-out is never sent (#63); closing a company is not built (#62) | `NotificationEventListener`, `NotificationDispatchService` · `NotificationDispatchServiceTest`, `NotificationDispatchAcceptanceTest` |
| I.5.b | UC-35 | Alert buyers in real time when a purchase completes, an event they hold tickets for is cancelled or rescheduled, or their active order is about to expire. | Partial: by polling (#61); order-expiring is never sent (#63); an event on sale cannot be rescheduled ([OQ-15](#open-questions)) | `NotificationEventListener`, `NotificationDispatchService` · `NotificationDispatchServiceTest`, `NotificationDispatchAcceptanceTest` |
| I.5.c | UC-35 | Alert members in real time when a message from an admin or a producer arrives. | Partial: by polling, not push (#61) | `MessagingService.sendOutreach`, `MessagingService.sendMessage`, `NotificationDispatchService` · `MessagingServiceTest`, `MessagingAcceptanceTest` |
| I.6 | UC-36, UC-37 | Delayed alerts: keep alerts for offline members and show them at their next login. | Built | `NotificationDispatchService.dispatch`, `NotificationDispatchService.deliverPending` · `NotificationDispatchServiceTest`, `DelayedAppointmentNotificationAcceptanceTest` |
| I.7 | — | Load management: above a load threshold, for the whole system or for one event, send visitors to a virtual waiting room and admit them in batches, and tell each waiting visitor in real time when they are admitted. | Deferred (#71) | — |

### II.1 Guest visitor

| ID | UC | Requirement | Status | Enforced by · tested by |
|---|---|---|---|---|
| II.1.1 | — | Enter as a guest. Choosing the first tickets opens an active order that holds them for a limited time. | Built | `AuthenticationService.startGuestSession`, `ReservationService` · `AuthenticationServiceTest`, `ReservationServiceTest` |
| II.1.2 | UC-2 | Exit. A guest's active order belongs to that visit only; its tickets are released when the hold expires. | Built; a guest's visit ends through idle expiry, since nothing calls `AuthenticationService.endGuestSession` | `SessionAndOrderSweeper` · `SessionAndOrderSweeperTest` |
| II.1.3 | UC-11 | Register as a member. The current visit stays a guest visit until login. | Built | `AuthenticationService.register` · `AuthenticationServiceTest`, `AuthAcceptanceTest` |
| II.1.4 | UC-12 | Log in as a member. | Built; the valid-login acceptance test is disabled (#51) | `AuthenticationService.login`, `JwtSessionManager` · `AuthenticationServiceTest`, `JwtSessionManagerTest` |

### II.2 Purchasing as a guest (members can do all of this too)

| ID | UC | Requirement | Status | Enforced by · tested by |
|---|---|---|---|---|
| II.2.1 | UC-3 | Get information on active production companies and their events. | Built | `CatalogService.searchGlobal`, `CatalogService.getEventDetail`, `CatalogService.upcomingOnSale` · `CatalogServiceTest` |
| II.2.1 (policies) | UC-3 | An event's information shows its purchase policy and its discount policy. | Deferred: `EventDetailDTO` carries no policy and no catalog view shows one (#193) | — |
| II.2.2 | UC-8 | See the event map and live inventory: the venue layout (stage, entrances, zones), overall availability for standing zones, and free or taken for each seat. | Partial: zones and live inventory are built; the layout is not modelled ([OQ-19](#open-questions), #194) | `CatalogService.getEventVenueMap` · `CatalogServiceTest`, `VenueMapTest` |
| II.2.3.a | UC-7 | Search all events by name, artist, category or keyword; filter by price, date, location, and event or company rating. | Built | `CatalogService.searchGlobal`, `EventSearchSpecification` · `CatalogServiceTest`, `IEventRepositoryContractTest` (Memory and Jpa), `SearchPresenterTest`; TODO `GivenEventInAnotherCity_WhenSearchAllByLocation_ThenExcluded` |
| II.2.3.b | UC-7 | Search within one company, with the same filters except company rating. | Partial: built in the backend, not offered in the UI (#69) | `CatalogService` · `CatalogServiceTest` |
| II.2.4 | UC-5, UC-9 | Reserve: tickets added to the active order are locked for the fixed hold, and nobody else can buy them meanwhile. | Built | `ReservationService`, `InventoryService` · `ReservationServiceTest`, `ReservationConcurrencyTest` |
| II.2.5.a | UC-5 | Choose specific numbered seats. | Built | `ReservationService` · `SeatedReservationJpaTest` |
| II.2.5.b | UC-9 | Choose a number of tickets in a standing zone. | Built | `InventoryService.reserve` · `ReservationServiceTest` |
| II.2.6 | — | Wait in a queue under extreme load, see a position or estimated wait, and get time-boxed access when admitted; access lapses, and the visitor leaves the queue, if they do not act in time. | Deferred (#71) | — |
| II.2.7 | — | View and change the active order (remove tickets, change quantities) until it expires. | Partial: viewing and removing are built; changing a quantity is #64 | `ReservationService` · `ReservationServiceTest` |
| II.2.8 | UC-10 | Check out under the company's purchase and discount policies before the hold expires. All or nothing: a payment or issuing failure, or an expired hold, buys nothing and releases the tickets. | Built for the policies that exist (II.4.3); money is a `double` (#73) | `CheckoutService` · `CheckoutServiceTest`, `CheckoutServiceAcceptanceTest` |

### II.3 Purchasing as a member

| ID | UC | Requirement | Status | Enforced by · tested by |
|---|---|---|---|---|
| II.3 (exit) | UC-13 | A member's active order survives a disconnect: logging in again before the hold expires resumes it; after expiry it is cancelled and the tickets released. | Partial: when a login finds both a guest cart and a held member order, which survives is [OQ-17](#open-questions) | `CartRestorationPort`, `CartRestorationAdapter`, `SessionAndOrderSweeper` · `CartRestorationAdapterTest`, `SessionAndOrderSweeperTest`, `AuthAcceptanceTest` |
| II.3.1 | UC-14 | Log out; the visit becomes a guest visit. | Built | `AuthenticationService.logout` · `SignOutFlowTest`, `AuthAcceptanceTest` |
| II.3.2 | UC-18 | Open a production company and become its Founder and first Owner. | Built | `CompanyManagementService.registerCompany` · `CompanyManagementServiceTest` |
| II.3.3 | — | File a complaint with the system admins (scalping, fraud, terms violations). | Built | `MessagingService` · `MessagingServiceTest`, `MessagingAcceptanceTest` |
| II.3.4 | UC-15 | View and change identifying details. | Partial: viewing is built; editing the profile and changing the password are #60 | `MyProfilePresenter`, `MyProfileView` · TODO `GivenSignedInMember_WhenLoadProfile_ThenUsernameAndEmailShown` |
| II.3.5 | UC-16 | See the personal purchase history: past purchases, and the current status of tickets for upcoming events. The purchased lines and the prices paid do not change when events, prices or companies change later. | Partial: `viewMyHistory` has no enabled test (its three cases in `MemberAccountServiceTest` are `@Disabled`), and it returns an empty history on any failure (#192). Names and venues are read live and stay fixed only because an event on sale cannot be edited ([OQ-15](#open-questions), [OQ-16](#open-questions)) | `MemberAccountService.viewMyHistory`, `OrderReceipt`, `ReceiptLine` · TODO implement and enable `MemberAccountServiceTest.givenPriceChangedAfterSale_whenViewMyHistory_thenOriginalPriceShown` (`@Disabled`, empty; #192) |
| II.3.6 | — | Register for a purchase-rights lottery on high-demand events. | Deferred (#72) | — |
| II.3.7 | — | Send an inquiry to a production company. | Built | `MessagingService.startConversation` · `MessagingServiceTest` |

### II.4 Owner or Founder

| ID | UC | Requirement | Status | Enforced by · tested by |
|---|---|---|---|---|
| II.4.1 | UC-19 | Manage events and inventory: add, edit and remove events; show dates; seated zones with rows and seats; standing-zone capacities. Show dates are in the event's time zone. | Built, with limits nobody has confirmed: details, show dates and inventory can be edited only before sales open, and only a cancelled event with no sales can be deleted ([OQ-15](#open-questions)) | `EventManagementService`, `Event`, `ShowDate`, `EventCompletionSweeper` · `EventManagementServiceTest`, `EventTest`, `ShowDateTest`, `EventCompletionSweeperTest`; TODO `GivenCancelledEventWithSales_WhenDeleteEvent_ThenRejected` |
| II.4.2 | UC-20 | Configure the venue map: the layout (stage, entrances, zones), and each zone linked to an inventory zone, either standing with a capacity or seated in blocks, rows and seats. The map shows free and taken places live. | Partial: zones link to inventory and show live status; stage, entrances and blocks are not modelled, and the map draws a fixed stage strip and two fixed gates (`VkVenueMap`; [OQ-19](#open-questions), #194) | `EventManagementService.configureVenueMap`, `VenueMap` · `EventManagementServiceTest`, `VenueMapTest`, `InventoryZoneTest` |
| II.4.3 | UC-21 | Set purchase methods and purchase and discount policies. | Partial: purchase policies are built at company and event level; discounts are one percentage per event, with no company-level discount; conditional, coupon and composed discounts are #67; the lottery purchase method is #72 | `EventManagementService.setEventPolicies`, `CompanyManagementService.setCompanyPolicies`, `PurchasePolicy`, `DiscountPolicy` (catalog) · `PurchasePolicyAcceptanceTest`, `PurchasePolicyEnforcementAcceptanceTest` |
| II.4.4 | — | Receive buyer inquiries and answer them. | Built | `MessagingService` · `MessagingAcceptanceTest` |
| II.4.5 | UC-22 | See the company's order and purchase history. It does not change when events, prices or companies change later. | Partial: prices are stored at sale; names and venues are read live and stay fixed only because an event on sale cannot be edited ([OQ-15](#open-questions), [OQ-16](#open-questions)) | `CompanyAnalyticsService.salesHistory`, `OrderReceiptMapper.toCompanySalesRecordDTO` · `CompanyAnalyticsServiceTest`, `CompanyAcceptanceTest`, `EventCancellationAcceptanceTest`; TODO `GivenPriceChangedAfterSale_WhenSalesHistory_ThenOriginalPriceShown` |
| II.4.6 | UC-22 | Produce a sales report of revenue and tickets sold, limited to the events the owner manages and their appointment subtree. | Partial: company-wide figures, not limited to the subtree (#68) | `CompanyAnalyticsService.dashboard`, `SalesSummaryCalculator` · `CompanyAnalyticsServiceTest`, `SalesSummaryCalculatorTest`, `CompanySalesReportAcceptanceTest` |
| II.4.7 | UC-24 | Appoint a manager with chosen permissions; the appointee must accept. | Built; the permission set is [OQ-2](#open-questions) | `CompanyManagementService.appointManager`, `CompanyMembershipService` · `ManagerPermissionMatrixAcceptanceTest`, `CompanyMembershipServiceLifecycleTest` |
| II.4.8 | UC-23 | Appoint a co-owner; the appointee accepts or rejects. | Built | `CompanyManagementService.appointOwner`, `CompanyMembershipService.receiveOwnerAppointment` · `CompanyMembershipServiceTest`, `DelayedAppointmentNotificationAcceptanceTest` |
| II.4.9 | — | Remove an owner you appointed. | Partial: `RevokeAppointment` revokes the owner; what happens to their appointees is undecided ([OQ-18](#open-questions), #65), and today they stay active under a non-owner, which can form a cycle and breaks the role tree (#126). No UI offers it | `CompanyManagementService.RevokeAppointment`, `CompanyAppointment.revoke` · TODO `GivenOwnerYouAppointed_WhenRevokeAppointment_ThenNoLongerOwner` |
| II.4.10 | — | Give up ownership; the Founder cannot. | Partial: an owner can revoke their own appointment through the same call, with the same open question (#65, #126); no UI | `CompanyManagementService.RevokeAppointment` · TODO `GivenNonFounderOwner_WhenRevokeOwnAppointment_ThenNoLongerOwner`, `GivenFounder_WhenRevokeOwnAppointment_ThenRejected` |
| II.4.11 | UC-24 | Change the permissions of a manager you appointed. | Built | `CompanyMembershipService` · `CompanyMembershipServiceLifecycleTest` |
| II.4.12 | UC-24 | Remove a manager you appointed. | Built | `CompanyManagementService.RevokeAppointment`, `CompanyAppointment.revoke` · `CompanyManagementServiceTest`, `CompanyMembershipServiceLifecycleTest` |
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
| II.6.3.a | — | View members' complaints and reply to them. | Built; a complaint takes one admin reply, which resolves it, and the member cannot answer ([OQ-20](#open-questions)) | `MessagingService.viewAllComplaints`, `MessagingService.respondToComplaint` · `MessagingServiceTest`, `MessagingAcceptanceTest` |
| II.6.3.b | — | Send system messages to producers and to buyers. | Built | `MessagingService.sendOutreach` · `MessagingServiceTest`, `MessagingAcceptanceTest` |
| II.6.4 | UC-31 | See the global purchase history, by buyer or by company and event. | Partial: with a company or event filter, the figures still count the other companies' lines of each matching order (#131) | `SystemAdminService.viewGlobalHistory`, `SalesSummaryCalculator` · `SystemAdminServiceTest`, `SalesSummaryCalculatorTest`; TODO enable `givenNonAdmin_whenViewGlobalHistory_thenRejected` (`@Disabled`) |
| II.6.5 | UC-46 | See live and historical analytics: visitor entries and exits, registrations, reservations and purchases. | Partial: entry, exit, registration and reservation counts are held in memory for 2 hours and reset on restart (`InMemorySystemMetrics`); only purchases, read from receipts, have longer history ([OQ-21](#open-questions), #195) | `SystemAnalyticsService.computeAnalytics`, `SystemAnalyticsService.adminOverview`, `InMemorySystemMetrics` · `SystemAnalyticsServiceTest`, `EventCancellationAcceptanceTest` |
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
| 1 | A member's username is unique. | **Partial.** Enforced by `AuthenticationService.register`; the database does not yet back the rule with a unique constraint. `SystemIntegrityVerifier` scans for duplicates · `AuthenticationServiceTest`, `SystemIntegrityVerifierTest` |
| 2 | At least one system admin exists. | `SystemAdminService.initializePlatform` · `SystemAdminServiceTest` |
| 3 | Every system admin is a member. | **Not upheld.** Admins are a separate pool, not members (`SystemIntegrityVerifier`). The deviation still needs its ADR (#44). |
| 4 | Company owners and managers are members. | `CompanyManagementService.appointOwner`, `SystemIntegrityVerifier` · `SystemIntegrityVerifierTest` |
| 5 | Only users who are currently visiting can act. | A valid session token on every write (`SessionManager`) · `JwtSessionManagerTest` (token validation only; no test asserts that every write is refused without a session) |
| 6 | An active company has at least one owner. | The Founder can be neither removed nor resign (`ProductionCompany`, `CompanyManagementService.RevokeAppointment`; no test covers these guards); scanned by `SystemIntegrityVerifier` · `SystemIntegrityVerifierTest` |
| 7 | Every company and every event has a purchase policy and a discount policy; where none is set, the purchase policy imposes no restriction and the discount policy gives no discount. | **Partial.** Purchase policy: `NoPurchasePolicy` by default on `ProductionCompany` and `Event`. Event discount: 0 % from `EventManagementService.addEvent`. Company discount: not built (#67). Purchase methods and their default (regular sale or lottery) are not modelled (#72). No test asserts the defaults. TODO `GivenNewCompanyAndEvent_WhenCreated_ThenPoliciesImposeNoRestrictionAndNoDiscount` |
| 8 | A buyer has one active order collecting their tickets, and at most one per event at any time. | Saving a second cart for the same buyer replaces the first (`ActiveOrderRepository`); scanned by `SystemIntegrityVerifier` · `IActiveOrderRepositoryContractTest`, `SystemIntegrityVerifierTest` |
| 9 | Adding a ticket to an active order holds it for a fixed time, `constants.ticket-reservation-duration` minutes (the course's example is 10). | `ReservationHoldConfig`, `ReservationService`; each reserved seat records the expiry its cart line had when it was reserved (`InventorySelection.holdUntil`) · `ReservationServiceTest`, `InventoryZoneTest` |
| 10 | An order not completed within that time is cancelled and its tickets return to stock. | `SessionAndOrderSweeper` and `ActiveOrderRepository.findExpired`, judged on the injected clock ([ADR-008](../ADRs/ADR-008-event-time-zones-and-one-clock.md)) · `SessionAndOrderSweeperTest`, `IActiveOrderRepositoryContractTest` |
| 11 | An active order belongs to its buyer; nobody else can change it before it expires. | `ReservationService`, `CheckoutService` · no test; ownership is structural (the order is looked up from the caller's own session). TODO `GivenAnotherBuyersSession_WhenChangeOrder_ThenOnlyTheCallersOrderChanges` |
| 12 | An event never sells more tickets than its inventory holds. | `StandingZone`, `SeatedZone`, `Ticket.reserve`, optimistic locking; scanned by `SystemIntegrityVerifier` · `ReservationConcurrencyTest`, `SystemIntegrityVerifierTest` |

### §2 External integrations

| # | Rule | Enforced by · tested by |
|---|---|---|
| 1 | When the platform initializes or the market opens, at least one payment service answers the handshake. | `SystemAdminService.initializePlatform`, `SystemAdminService.openMarket` · `SystemAdminServiceTest` (`givenNoReachablePaymentGateway_whenInitializePlatform_thenThrows`, `givenPaymentDownAtOpen_whenOpenMarket_thenThrowsAndStaysReady`) |
| 2 | When the platform initializes or the market opens, at least one ticket-issuing service answers the handshake. | `SystemAdminService.initializePlatform`, `SystemAdminService.openMarket` · `SystemAdminServiceTest` (`givenNoReachableTicketIssuer_whenInitializePlatform_thenThrows`, `givenIssuerDownAtOpen_whenOpenMarket_thenThrowsAndStaysReady`) |

### §3 Commercial integrity rules

The course states §3 #3 and §3 #4 as one bullet; the code cites them separately.

| # | Rule | Enforced by · tested by |
|---|---|---|
| 1 | Charge buyers only for completed transactions, and only the declared amounts. | **Partial.** `CheckoutService.validatePaymentResult` rejects a result whose amount or currency differs from the request, but `WsepPaymentGateway.charge` echoes the requested amount, so the check cannot fire under WSEP; no test covers the mismatch branch (TODO `GivenGatewayChargesADifferentAmount_WhenCheckout_ThenPurchaseFails`). A `pay` call whose answer is lost after WSEP charged is reported as unreachable, and nothing records the charge for reconciliation (#99) |
| 2 | A company receives money only from a successful purchase. | `CheckoutService` · `CheckoutServiceTest` |
| 3 | A purchase succeeds if and only if both the payment and the ticket-issuing services approve it. | `CheckoutService` · `CheckoutServiceTest`, `CheckoutServiceAcceptanceTest` |
| 4 | When payment succeeds but issuing fails, refund automatically and tell the buyer. | **Partial.** `CheckoutService` requests the refund and publishes `PurchaseFailedNotice` to members, delivered at most once ([ADR-004](../ADRs/ADR-004-no-outbox-for-notifications.md)). A refund call that fails is only logged, with no record for recovery (#130). Tickets WSEP already issued are not cancelled ([OQ-14](#open-questions)). Guests are told only on the checkout screen until #102 · `CheckoutServiceTest`, `CheckoutServiceAcceptanceTest` |
| 5 | Obey the law: issue receipts, prevent illegal resale, never sell 18+ events to minors. | Receipts: `OrderReceipt`. Age: `AgePurchasePolicy`, applied only where the company or event sets one ([OQ-6](#open-questions)). Resale: only a per-purchase, per-event cap (`MaxTicketsPurchasePolicy` counts the tickets in one checkout); nothing limits a buyer across purchases; see [OQ-10](#open-questions). |

### Roles and appointments

- A user holds at most one active role per company. `CompanyMembershipService` refuses a second
  active or pending appointment, except a pending owner offer to an active manager, which replaces
  the manager role when accepted.
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
- Only the company's owners and the system admins can see a closed company's information. Its
  owners and managers are alerted when it closes and when it reopens, and its appointment tree is
  kept (II.4.13, II.4.14).
- What happens to a company whose Founder's membership is cancelled is [OQ-23](#open-questions).

### Time and event schedule

- Show times are wall-clock times at the venue, read in the event's IANA time zone. A new event
  defaults to `platform.time-zone`. Every comparison with "now" turns a show time into an instant
  first, so no answer depends on the server's zone ([ADR-008](../ADRs/ADR-008-event-time-zones-and-one-clock.md);
  `ShowDate.startsAt`, `ShowDate.endsAt` · `ShowDateTest`).
- A new or edited show must start after the current time at the venue. A show that has already
  happened stays valid (`Event.requireShowsStartAfter`, `Event.editDetails` · `EventTest`,
  `EventManagementServiceTest`).
- A live event completes once its last show has ended at the venue (UC-19;
  `EventCompletionSweeper`, `Event.hasFinishedAsOf` · `EventCompletionSweeperTest`, `EventTest`).
- No behaviour depends on the server's time zone. Every "now" comes from one UTC clock, and the UI
  shows recorded times and "today" in `platform.time-zone` (`SecurityConfig.systemClock`,
  `DisplayTime` · `HexagonalRulesTest`, `ClockConfigTest`, `DisplayTimeTest`; CI runs the suite in UTC
  and in Asia/Jerusalem).

### Purchase flow

- A hold lasts `constants.ticket-reservation-duration` minutes. Expired holds return to stock
  (`SessionAndOrderSweeper`).
- Checkout is all or nothing: a failure before the sale commits releases the tickets and requests a
  refund of any charge (`CheckoutService`). §3 #1 and §3 #4 name the cases where the refund is not
  guaranteed.
- Cancelling an event refunds each of its receipt lines at most once. If the payment service
  declines a refund or cannot be reached, the event stays cancelled and the lines refunded so far
  stay refunded; cancelling it again refunds the rest. A refund the payment service may have carried
  out without a usable answer is never retried automatically; it stays PENDING, and no screen or
  command resolves it yet (#130; [ADR-001](../ADRs/ADR-001-refund-state-per-receipt-line.md);
  `EventCancellationRefundService` · `EventCancellationRefundServiceTest`,
  `EventCancellationRefundConcurrencyTest`, `EventCancellationAcceptanceTest`).
- A member's whole-order refund is refused once any line of the order has been refunded or is
  being refunded by an event cancellation (`RefundService` · `RefundServiceTest`). **Not upheld**
  when the payment service's answer is lost: the refund rolls back to unrefunded and the member can
  request it again (#130).
- A guest's order ends with the visit; a member's lasts until the hold expires.
- Receipts and purchase history never change when events, prices or companies change later
  (II.3.5, II.4.5). Receipts store the price at purchase (`OrderReceipt`, `ReceiptLine`).
- A company's sales history shows only its own tickets and the revenue from them. An order's
  payment transactions cover the whole order, which may hold other companies' tickets, so no
  company sees them on any order. The buyer's own history (II.3.5) and the admin global history
  (II.6.4) do (#121; `OrderReceiptMapper.toCompanySalesRecordDTO` · `CompanyAnalyticsServiceTest`,
  `CompanyAcceptanceTest`). Revisit if payments are ever allocated per line, for example for
  per-company payouts: only then can a company be shown its own share of a payment.
- Refunds are tracked per receipt line. Revenue and tickets sold count only the lines that are not
  refunded, so cancelling one event of a multi-event or shared order removes only that event's lines
  from the figures. Each view reports an order as paid, partly refunded or refunded, judged on the
  lines that view shows, so a company is not shown another company's refund. The exception is an
  order refunded before refunds were tracked per line (#20): it recorded only an order-level flag,
  so all its lines count as refunded, failing closed as ADR-001 decides (#21;
  `CompanyAnalyticsService.dashboard`, `SystemAnalyticsService.adminOverview`, `SalesSummaryCalculator`,
  `OrderReceiptMapper` · `CompanyAnalyticsServiceTest`, `SystemAnalyticsServiceTest`,
  `SalesSummaryCalculatorTest`, `EventCancellationAcceptanceTest`).

### Purchase policies and discounts

- A purchase policy is a condition on the buyer's age and the number of tickets (minimum or
  maximum). Conditions combine with AND and OR to any depth, and a policy is set per company, per
  event, or both (`PurchasePolicy`, `AndPurchasePolicy`, `OrPurchasePolicy`, `AgePurchasePolicy`,
  `MinTicketsPurchasePolicy`, `MaxTicketsPurchasePolicy` · `PurchasePolicyAcceptanceTest`).
- Discounts (V2) are percentages. There are three kinds:
  - simple;
  - conditional, which applies only while its condition holds; V2 requires at least a minimum or
    maximum number of tickets and a date range;
  - coupon, which applies when the buyer enters a code at checkout within its validity.

  Discounts combine by **maximum** (no double discounts) or **sum**, to any depth. Only one
  percentage per event is built (`DiscountPolicy`); the rest is #67.
- A discount applies to a whole company, one event, or one show of an event (V2). Deferred (#67);
  the wider scopes in the general document are [OQ-24](#open-questions).
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
| SLR.1 | Consistency and concurrency: after every action, startup included, the business rules above hold; reserving and purchasing are atomic; no seat or ticket is sold to two buyers. | Partial: the concurrency proofs run on H2 only (#171); username uniqueness is not yet backed by the database (§1 #1); a member refund whose answer is lost can be repeated (#130) | Optimistic locking, `OptimisticLockTranslationAspect`, per-line refund claims ([ADR-001](../ADRs/ADR-001-refund-state-per-receipt-line.md)) · `ReservationConcurrencyTest`, `RefundConcurrencyTest`, `EventCancellationRefundConcurrencyTest`, `OptimisticLockTranslationAspectTest` |
| SLR.2 | Privacy and security: identifying data is neither exposed nor recoverable; passwords are stored hashed; requests after login carry a token; passwords are masked in the UI. | Partial: session-id rotation #75, default secrets #34, admin credentials #77 | `BcryptPasswordHasher`, `JwtSessionManager` · `AuthenticationServiceTest` (including `GivenTakenEmail_WhenRegister_ThenErrorMessageOmitsTheEmail` and `GivenMalformedEmail_WhenRegister_ThenErrorMessageOmitsTheEmail`: error messages, which are logged, carry no email address), `JwtSessionManagerTest`, `BcryptPasswordHasherTest` |
| SLR.3 | User experience: 3.1 response time holds under load; 3.2 help with common errors; 3.3 a convenient UI; 3.4 each role sees only the actions it may perform; 3.5 the UI explains successes and failures (V2). | Partial: 3.1 has no target ([OQ-11](#open-questions)); 3.4 depends on a mock capability resolver (#59); accessibility #110; for 3.3, browse filters kept in the URL #70 | `AuthBootstrap`, `RequireCapability`, `Capability` · `CapabilitiesTest` (3.4), `GlobalUiErrorHandlerTest` (3.5, unexpected errors only), `RegisterPresenterTest` (3.5, registration outcomes) |
| SLR.4 | Load, stress and availability: unlimited visitors, companies and events; spikes when sales open; continuous availability. | TODO: no targets set ([OQ-11](#open-questions)). Load test #108; waiting queue #71 | — |
| SLR.5 | Robustness: keep working under load, network failures and the loss of an external service. | Partial: external-call timeouts and handshake retries; an event cancellation interrupted by a payment-service failure resumes when the owner cancels again; notifications are delivered after commit on a background thread, so a slow or failing alert never blocks, fails or rolls back a use case ([ADR-004](../ADRs/ADR-004-no-outbox-for-notifications.md)). Gaps: a refund whose outcome is unknown can be repeated and cannot be resolved (#130); a lost answer to `pay` is never reconciled (§3 #1, #99); with 1,000 notifications queued, a new one is dropped (ADR-004). Targets: TODO, [OQ-22](#open-questions) | `WsepHttpClient`, `EventCancellationRefundService`, `NotificationEventListener`, `NotificationAsyncConfig` · `WsepHttpClientRetryTest`, `EventCancellationRefundServiceTest`, `EventCancellationRefundConcurrencyTest`, `NotificationDispatchAcceptanceTest`, `DropAndLogRejectionHandlerTest` |
| SLR.6 | Recovery: return to normal operation after a fault, for example resume automatically when the database comes back. | Partial: the pool recovers without a restart once the database returns, but production bounds no read already in flight, so a hung database can hold connections until the OS TCP timeout (#173). Targets: TODO, [OQ-22](#open-questions) | HikariCP's connection validation (its defaults in every run; tuned in `application-supabase.yml`, which #97 retires), `MarketSelfHealScheduler` (`market.self-heal-delay-ms`, 30 s) · `DbOutageRecoveryIT` (a paused PostgreSQL container, in CI), `MarketSelfHealSchedulerTest` |
| SLR.7 | Persistence: state survives a planned or unplanned shutdown, except state the Non-goals call transient. V3: a remote database is supported, and switching databases is a configuration change only. | Partial: built, and the default in every run since #28 (ADR-005). The schema is a set of versioned migrations, applied at startup on every database; the application refuses to start when a table or column its mapping needs is missing or has an incompatible type, or when the schema was not created by the migrations (#31, [ADR-007](../ADRs/ADR-007-flyway-schema-migrations.md)). Market state (#24), idempotency keys (#25), login lockouts (#39) and the system metrics behind II.6.5 (`InMemorySystemMetrics`, a 2-hour window; #195) are held in memory | The JPA repository adapters and their contract tests, on H2 and on PostgreSQL (`Postgres…IT`); the Flyway migrations in `db/migration` with `ddl-auto: validate` · `SchemaMigrationIT`, `RepositoryAdapterProfileTest`, `DatabaseUnavailableFailureAnalyzerTest`, `TicketIdRestartJpaTest` |
| SLR.8 | Tracking: 8.1 operational measurements; 8.2 error tracking, preferably in real time. V1 adds an event log and an error log. | Partial: one combined log file. The use-case trace (`LoggingAspect`: method and argument count, never values, per SLR.2) runs only at DEBUG, so production keeps no event log; a failed use case is logged at WARN with its exception message, which must therefore carry no personal data; V1's "with parameters" conflicts with SLR.2 ([OQ-26](#open-questions)). Correlation ids, JSON logs and a separate error stream #30; metrics and traces #37. Real-time target: TODO, [OQ-22](#open-questions) | `LoggingAspect`, `logback-spring.xml` |

### Course delivery requirements (V1–V3)

| ID | Requirement | Status | Enforced by · tested by |
|---|---|---|---|
| V1 1.d | Keep an event log of the use cases invoked and an error log, always available. | Partial: see SLR.8 | — |
| V1 impl 5 | CI runs every test before code reaches `main`. | Built | `.github/workflows/ci.yml` |
| V1 impl 6.a | Concurrency tests start many threads at once against a sensitive operation and check the state afterwards, whichever locking is used. | Partial: on H2 only (#171) | `ReservationConcurrencyTest`, `RefundConcurrencyTest`, `EventCancellationRefundConcurrencyTest` |
| V1 impl 6.b | Acceptance tests reach the system through the application layer only. | Partial: 12 of the 16 acceptance classes also read or seed repositories directly; the test-suite scan (#189) audits it | `acceptance/` |
| V2 3.a | Report test coverage automatically, for example with JaCoCo. | Deferred (#45); exempted under V2 Tier B in the course project | — |
| V2 3.b | Tests cover every requirement, including real-time and delayed alerts. | Partial: 50 tests are `@Disabled` (#52) | — |
| V2 1, V3 impl 6 | A communication component maps HTTP requests to the application layer. | TODO: [OQ-25](#open-questions) | — |
| V3 2.a.i | Initialize from external configuration files; database connection details live in configuration, not code. | Partial: every value in `application.yml` can be overridden from the environment or an external file (`spring.config.import`, `--spring.config.additional-location`); most initialization parameters bind to validated records, and an out-of-bounds value stops startup ([ADR-003](../ADRs/ADR-003-fatal-and-self-healing-init-failures.md)). Still unvalidated: `analytics.*`, `sweeper.fixed-delay-ms`, `notifications.poll-delay-ms` and `event-completion.fixed-delay-ms` (#165), and `jwt.secret` (#34). `constants.order-expiration-duration` is read by no code (#166). The queue threshold, the course's example parameter, does not exist yet (#71) | `application.yml`, `ReservationHoldProperties`, `SessionProperties`, `LockoutProperties`, `WsepProperties`, `PlatformAdminProperties`, `MarketProperties` · `InitPropertiesValidationTest` |
| V3 2.a.ii | Optionally initialize from an initial-state file that replays use cases through the application layer. If any step fails, initialization fails. | Built: the `.scenario` engine runs in every profile except `test` when `seed.mode` is set. A missing or unreadable file stops startup in every profile; a failing step stops it while `seed.fail-fast` is on, the default everywhere but `dev` ([ADR-003](../ADRs/ADR-003-fatal-and-self-healing-init-failures.md)) | `ScenarioRunner`, `ScenarioOps`, `SeedProperties` · `ScenarioRunnerTest` |
| V3 2.b | Connect to the real external payment and ticket-issuing systems. | Built (WSEP); Stripe replaces WSEP payments in #98 | `WsepPaymentGateway`, `WsepTicketIssuer` · `WsepPaymentGatewayTest`, `WsepTicketIssuerTest` |
| V3 impl 3 | The README explains how to initialize the system and defines the configuration and initial-state file formats. | Built | `README.md` (configuration), `SEED.md` ("Scenario file format") |
| V3 impl 4.a | Resilience tests cover the external systems and the database. | Built | `WsepHttpClientRetryTest`, `DbOutageRecoveryIT` |
| V3 impl 4.b | Tests prove the system does not start after an invalid initialization. | Built: invalid configuration, a failed platform initialization and a failing initial-state file each stop startup; a WSEP outage does not, and self-heals ([ADR-003](../ADRs/ADR-003-fatal-and-self-healing-init-failures.md)) | `InitPropertiesValidationTest`, `PlatformInitializationRunnerTest`, `ScenarioRunnerTest`, `SystemAdminServiceTest`, `MarketSelfHealSchedulerTest` |
| V3 impl 4.c | Tests use a dedicated configuration and never reach the real external systems or the production database. | Built: the `test` profile and `application-test.yml` swap in stub gateways; the `*IT` classes use a throwaway PostgreSQL container per class | `application-test.yml`, `PostgresContainers` · every test that runs under the `test` profile, and every `*IT` |
| V3 appendix 3 | Use the ORM's built-in cache. | TODO: [OQ-12](#open-questions), evaluated in #33 | — |
| V3 appendix 4 | Each use case runs as one transaction. | Partial: most application services are one `@Transactional` method. Checkout calls the external services outside the transaction and commits afterwards (ADR pending, #44). Event-cancellation refunds claim and record each receipt in its own short transaction around the gateway call ([ADR-001](../ADRs/ADR-001-refund-state-per-receipt-line.md)). The member refund holds the receipt's row lock across the gateway call by design (upstream `AdamSimkinbgu/event-ticket-system#410`; see ADR-001) | `CheckoutService`, `EventCancellationRefundService`, `RefundService` |

## Non-goals

- **Committing the course documents.** They are the university's material and this repository is
  public.
- **Persisting waiting-queue state.** Queues are transient (V3).
- **Designing the database independently of the domain.** The ORM maps the domain model onto
  tables, never the reverse (V3).
- **Guaranteed notification delivery.** Alerts are delivered at most once: a crash, or a queue
  already holding 1,000 notices, loses them
  ([ADR-004](../ADRs/ADR-004-no-outbox-for-notifications.md) names the triggers to revisit).
- **Automatically retrying a refund whose outcome is unknown,** until the payment adapter supports
  idempotency keys ([ADR-001](../ADRs/ADR-001-refund-state-per-receipt-line.md); #98, #99).
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
| OQ-6 | **Buyer age.** Age policies need it, but guests have no stored age, so the code defers the check to checkout and refuses an unknown age (`AgePurchasePolicy`). A member's age is a whole number entered at registration that never advances (`User.age`), and both are self-declared. An 18+ event is protected only if its producer sets an age policy (§3 #5), a legal obligation. Options: keep this; store a date of birth; verify age; let the platform mark events adults-only. | `AgePurchasePolicy`, `User` |
| OQ-7 | **Lottery and queue parameters** (#71, #72): draw method, number of winners, code lifetime; a per-event or system-wide threshold; admission rate. | — |
| OQ-8 | **Rules found only in code.** Confirm or change them: a password has at least 8 characters with a letter and a digit; 5 failed sign-ins lock a member or admin account for 15 minutes; a member session lasts 24 hours, a guest session ends after 30 idle minutes; a username is 3–32 characters from letters, digits, `.`, `_` and `-`, checked only in the UI; reserved usernames are refused only in the UI (#76); an email is required, well-formed and unique; age is a whole number entered at registration; company names are unique; company and event purchase policies both apply; a member's refund covers the whole order, and is refused once any line of it has been refunded (for example by a cancelled event); a manager can be promoted to owner only by their own appointer; expired holds are released by a sweep every 60 s, so a seat can stay taken up to about a minute past its hold; renewing every hold of an order when the buyer enters checkout is built and tested but nothing calls it (keep, bound or delete it?); `constants.order-expiration-duration` (60 minutes) is configured but read by no code (#166). | `AuthenticationService`, `UsernameAvailabilityPresenter`, `CompanyManagementService.registerCompany`, `ReservationService`, `application.yml` |
| OQ-9 | **Resolved (#121).** A company sees no payment transactions on any order, only its own tickets and revenue; see [Purchase flow](#purchase-flow). | `OrderReceiptMapper.toCompanySalesRecordDTO` |
| OQ-10 | **Anti-scalping** (§3 #5). The only control is a per-purchase, per-event cap (`MaxTicketsPurchasePolicy` counts the tickets in one checkout), so a buyer can repeat purchases, and nothing detects bots, though the general document asks admins to detect and block them. Options: keep the per-purchase cap; add a cumulative per-buyer cap; verify identity; rate-limit (#109) or score abuse (#142); add resale rules. | `MaxTicketsPurchasePolicy` |
| OQ-11 | **Load, response-time and availability targets** (SLR.3.1, SLR.4). The course asks for "unlimited" visitors, response times that hold under load, and continuous availability "except planned shutdowns", with no numbers. What peak concurrency, response time per key flow, availability, and longest acceptable downtime for a deploy or a service replacement must launch meet? Today the platform runs as one instance with in-memory market state. Feeds #108. | — |
| OQ-12 | **ORM cache** (V3 appendix 3). Is Hibernate's second-level cache required for catalog reads? Evaluated in #33. | — |
| OQ-13 | **Several payment and issuing providers** (I.3, I.4). The course requires support for more than one of each. Checkout wires exactly one `PaymentGateway` and one `TicketIssuer`, and #98 and #100 replace WSEP rather than add a provider. Is one swappable provider of each kind enough (then "more than one" becomes a non-goal), or must several be active at once, with routing or failover? | `CheckoutService` |
| OQ-14 | **Issued tickets on rollback and refund** (I.4, §3 #2, §3 #4). WSEP offers `cancel_ticket` for system-initiated rollbacks and the code never calls it, so a checkout that fails after issuing, a member refund and an event cancellation all leave the issued codes valid at the issuer. Cancel at the issuer on every rollback and refund; rely on our own ticket status at check-in (#101); or accept? | `CheckoutService`, `WsepTicketIssuer` |
| OQ-15 | **Changing an event that is on sale** (II.4.1, I.5.b). The code lets an owner edit details, show dates and inventory only before sales open, and delete only a cancelled event with no sales, so I.5.b's "event rescheduled" alert never fires. The course's history notes assume dates and venues do change. Forbid it (and drop "rescheduled" from I.5.b); allow it, with a buyer alert and the venue and time kept on each receipt line; or allow date changes only? | `Event.editDetails`, `EventManagementService.deleteEvent` |
| OQ-16 | **What purchase history may change** (II.3.5, II.4.5). The course says history ignores later changes, event cancellation included, yet the views mark refunded lines, and event, zone, venue and company names are read live (only the price is stored on the receipt line). May only the refund status change; nothing (refunds shown as separate entries); or refund status plus live names? | `OrderReceiptMapper`, `ReceiptLine` |
| OQ-17 | **A guest cart and a held member order at login** (II.3 exit, §1 #8). When a member who holds an order fills a guest cart in a new visit and then logs in before the hold expires, the guest cart replaces the held order. Keep the guest cart; keep the member order; merge them, subject to §1 #8; or ask the buyer? | `CartRestorationAdapter` |
| OQ-18 | **A removed or resigning owner's appointees** (II.4.9, II.4.10, #65). The course does not say what happens to them. Cascade-revoke the whole subtree; reassign them to the remover; or refuse while appointees exist? | `CompanyManagementService.RevokeAppointment` |
| OQ-19 | **Venue layout** (II.2.2, II.4.2, #194). The course has the owner place the stage and the entrances and define seated zones in blocks. Must launch model them, defer them and stop drawing a fixed stage and gates, or declare them a non-goal? | `VkVenueMap`, `VenueMap` |
| OQ-20 | **Complaint replies** (II.6.3.a). The code allows exactly one admin reply, which resolves the complaint, and the member cannot answer it. Keep that, or allow a threaded conversation until an admin closes it? | `MessagingService.respondToComplaint` |
| OQ-21 | **Analytics history** (II.6.5, #195). How far back must visit, registration and reservation figures go, and must they survive a restart? Today they are kept in memory for 2 hours. | `InMemorySystemMetrics` |
| OQ-22 | **Robustness, recovery and tracking targets** (SLR.5, SLR.6, SLR.8.2). The course gives no numbers. Today the code waits 20 s per WSEP call (8 s in `dev`), tries the handshake 3 times 1 s apart, retries market self-heal every 30 s, and uses HikariCP's defaults. What is the longest a buyer may wait on an external service, the recovery time once the database returns, and how fast must an error reach someone? Feeds #108 and #92. | `WsepHttpClient`, `MarketSelfHealScheduler` |
| OQ-23 | **Cancelling a Founder's membership, and suspended members** (II.6.2, II.6.7, §1 #6). Cancelling a membership cancels all its roles, yet an active company needs an owner. Does the Founder's company close? What happens to a suspended member's active order and roles? Feeds #62 and #66. | — |
| OQ-24 | **Purchase-policy and discount scope beyond V2** (#67). The general document also mentions a "no single empty seat" purchase rule, discounts on a ticket type or a group of events, and showing the original price with the offer's end date. Which are required? | `PurchasePolicy`, `DiscountPolicy` |
| OQ-25 | **A public HTTP API, and the course's process deliverables.** V2 and V3 list a "rest API" communication component; Ticketeer's only driving adapter is the Vaadin UI. Is an HTTP API required (for example for #99's webhooks), or a non-goal? Are the course's V0/V1 process deliverables (models, version tags, per-version milestones) tracked here or out of scope? | — |
| OQ-26 | **Event log versus privacy** (SLR.8, SLR.2). V1 wants every use case logged with its parameters; SLR.2 forbids exposing identifying data. Today the use-case trace logs argument counts only, but a failed use case is logged at WARN with its exception message, and sign-in and registration log lines carry the raw username input, which can be an email address. Log user ids only; redact at the logging boundary; or drop V1's "with parameters"? | `LoggingAspect`, `AuthenticationService` |
