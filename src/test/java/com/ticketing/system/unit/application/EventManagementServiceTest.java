package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.ticketing.system.identity.application.port.out.SessionManager;
import org.springframework.context.ApplicationEventPublisher;
import com.ticketing.system.catalog.domain.event.EventCancelledEvent;
import com.ticketing.system.catalog.application.port.out.EventSalesHistoryPort;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.catalog.config.VenueLimitsProperties;
import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.adapter.out.persistence.MemoryCompanyAppointmentRepository;
import com.ticketing.system.organization.application.service.CompanyMembershipService;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.catalog.application.dto.EventCreationDTO;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.shared.exception.CompanyNotFoundException;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.domain.InventoryZone;
import com.ticketing.system.catalog.domain.StandingZone;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.Seat;
import com.ticketing.system.catalog.domain.SeatedZone;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.catalog.domain.VenueMap;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.sales.domain.TransactionRecord;
import com.ticketing.system.shared.dto.ZoneDetailDTO;
import com.ticketing.system.shared.dto.VenueLayoutDTO;
import com.ticketing.system.shared.dto.GridPlacementDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO;
import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.shared.dto.EventUpdateDTO;
import com.ticketing.system.shared.dto.LocationDTO;
import com.ticketing.system.shared.dto.ShowDateDTO;
import com.ticketing.system.catalog.domain.DiscountPolicy;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.shared.domain.policy.AgePurchasePolicy;
import com.ticketing.system.shared.domain.policy.AndPurchasePolicy;
import com.ticketing.system.shared.domain.policy.MaxTicketsPurchasePolicy;
import com.ticketing.system.shared.domain.policy.MinTicketsPurchasePolicy;
import com.ticketing.system.shared.domain.policy.NoPurchasePolicy;
import com.ticketing.system.shared.domain.policy.OrPurchasePolicy;
import com.ticketing.system.shared.domain.policy.PurchaseContext;
import com.ticketing.system.shared.domain.policy.PurchasePolicy;
import com.ticketing.system.shared.dto.EventPolicyConfigDTO;
import com.ticketing.system.shared.dto.PurchasePolicyDTO;

class EventManagementServiceTest {

        private EventRepository mockEventRepo;
        private ProductionCompanyRepository mockCompanyRepo;
        private SessionManager sessionManager;
        private EventManagementService eventService;
        private EventSalesHistoryPort eventSalesHistoryPort;
        private UserRepository userRepository;
        // Real membership service over an in-memory appointment repo — seeds/answers company-membership
        // permission checks now that appointments live off the User aggregate (task #20).
        private CompanyMembershipService membershipService;
        private ApplicationEventPublisher eventPublisher;

        private final String OWNER_TOKEN = "owner-token";
        private final String MANAGER_TOKEN = "manager-token";                 // CONFIGURE_VENUE manager
        private final String MANAGE_INVENTORY_TOKEN = "inventory-manager-token"; // MANAGE_INVENTORY manager
        private final String INVALID_TOKEN = "invalid-token";

        private final int COMPANY_ID = 100;
        private final int EVENT_ID = 10;
        private final int OWNER_ID = 1;
        private final int MANAGER_ID = 2;
        private final int INVENTORY_MANAGER_ID = 3;
        private final int ZONE_ID = 5;
        private final int ORDER_RECEIPT_ID = 20;
        private final String COMPANY_1_NAME = "Company1";
        private final String COMPANY_1_DESCRIPTION = "A test production company1";
        private final Location LOCATION = new Location("Belgium", "Brussels");
        private static final ZoneId JERUSALEM = ZoneId.of("Asia/Jerusalem");
        // 16:30Z on 15 July 2027 is 19:30 in Jerusalem (IDT, UTC+3).
        private static final Instant JERUSALEM_1930 = Instant.parse("2027-07-15T16:30:00Z");
        // The venue-limit tests' "now": before any show the shared event fixture schedules, since that
        // fixture dates its shows from the system clock.
        private static final Instant VENUE_LIMITS_NOW = Instant.parse("2026-01-01T00:00:00Z");

        private ProductionCompany company;
        private InventoryZone zone;
        private VenueMap venueMap;
        private Event event;
        private User ownerUser;
        private User managerUser;
        private User inventoryManagerUser;

        @BeforeEach
        public void setUp() {
                mockEventRepo = mock(EventRepository.class);
                mockCompanyRepo = mock(ProductionCompanyRepository.class);
                sessionManager = mock(SessionManager.class);
                eventSalesHistoryPort = mock(EventSalesHistoryPort.class);
                userRepository = mock(UserRepository.class);
                eventPublisher = mock(ApplicationEventPublisher.class);
                membershipService = new CompanyMembershipService(new MemoryCompanyAppointmentRepository(), Clock.systemUTC());

                // The fixtures below are dated relative to the real clock, so the shared service reads it.
                eventService = serviceWith(Clock.systemUTC());

                company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME, CompanyStatus.ACTIVE,
                                COMPANY_1_DESCRIPTION, 4.5);
                zone = new StandingZone(ZONE_ID, "VIP", 10, 100);
                venueMap = new VenueMap(1, LOCATION, List.of(zone));
                event = new Event(
                                EVENT_ID,
                                "Concert",
                                4.5,
                                List.of("Artist1"),
                                EventCategory.CONCERT,
                                COMPANY_ID,
                                EventStatus.SCHEDULED,
                                venueMap,
                                List.of(new ShowDate(LocalDateTime.now().plusDays(10),
                                                LocalDateTime.now().plusDays(10).plusHours(2))),
                                ZoneId.of("Asia/Jerusalem"),
                                null,
                                new DiscountPolicy(0));
                ownerUser = new User(OWNER_ID, "Owner Name", "owner@example.com", "hashedpassword", 50);
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);
                managerUser = new User(MANAGER_ID, "Manager Name", "manager@example.com", "hashedpassword", 40);
                membershipService.receiveManagerAppointment(MANAGER_ID, COMPANY_ID, OWNER_ID,
                                List.of(Permission.CONFIGURE_VENUE));
                membershipService.acceptInvitation(MANAGER_ID, COMPANY_ID);
                inventoryManagerUser = new User(INVENTORY_MANAGER_ID, "Inventory Manager",
                                "inventory@example.com", "hashedpassword", 40);
                membershipService.receiveManagerAppointment(INVENTORY_MANAGER_ID, COMPANY_ID, OWNER_ID,
                                List.of(Permission.MANAGE_INVENTORY));
                membershipService.acceptInvitation(INVENTORY_MANAGER_ID, COMPANY_ID);
        }

        // The shipped catalog.venue-limits.* defaults (application.yml), so every other test runs under
        // the limits a real boot applies.
        private static final VenueLimitsProperties SHIPPED_VENUE_LIMITS =
                        new VenueLimitsProperties(12, 49_500, 1_000_000, 100_000);

        private EventManagementService serviceWith(Clock clock) {
                return serviceWith(clock, SHIPPED_VENUE_LIMITS);
        }

        private EventManagementService serviceWith(Clock clock, VenueLimitsProperties venueLimits) {
                return new EventManagementService(mockEventRepo, mockCompanyRepo, sessionManager,
                                eventSalesHistoryPort, userRepository, membershipService, eventPublisher, clock,
                                venueLimits);
        }

        // Catalog-only happy path for cancelEventAndRefund: valid owner, event, and company. The
        // refund/ticket/notification work now lives in the sales listener, so this no longer stubs any
        // sales port. A real OrderReceipt is still built and returned so idempotency tests can assert on it.
        private OrderReceipt setupStateBasedHappyPath() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(userRepository.getUserById(MANAGER_ID)).thenReturn(managerUser);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                ReceiptLine line = new ReceiptLine(1, 100.0, EVENT_ID, 1, "A1", java.time.LocalDateTime.now());
                OrderReceipt realReceipt = OrderReceipt.forMember(99, OWNER_ID, 100.0, List.of(line), LocalDateTime.now());
                realReceipt.addTransaction(
                                TransactionRecord.paymentCharge(42, "test-gateway", 100.0, "ILS",
                                                java.time.LocalDateTime.now()));

                return realReceipt;
        }

        @Test
        public void GivenInvalidToken_WhenCancelEventAndRefund_ThenThrowException() {
                when(sessionManager.validateToken(INVALID_TOKEN)).thenReturn(false);

                assertThrows(RuntimeException.class, () -> eventService.cancelEventAndRefund(INVALID_TOKEN, EVENT_ID));
        }

        @Test
        public void GivenEventDoesNotExist_WhenCancelEventAndRefund_ThenThrowException() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenThrow(RuntimeException.class);

                assertThrows(RuntimeException.class, () -> eventService.cancelEventAndRefund(OWNER_TOKEN, EVENT_ID));
        }

        @Test
        public void GivenCompanyDoesNotExist_WhenCancelEventAndRefund_ThenThrowException() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenThrow(RuntimeException.class);

                assertThrows(RuntimeException.class, () -> eventService.cancelEventAndRefund(OWNER_TOKEN, EVENT_ID));
        }

        // #20: cancelling an already-cancelled event republishes the cancellation so the sales side
        // resumes the refunds a failure interrupted; the CANCELED state is not saved a second time.
        @Test
        public void GivenEventAlreadyCanceled_WhenCancelEventAndRefund_ThenRefundsResumeWithoutResaving() {
                setupStateBasedHappyPath();
                event.transitionToCanceled("cancelled");

                eventService.cancelEventAndRefund(OWNER_TOKEN, EVENT_ID);

                verify(eventPublisher).publishEvent(new EventCancelledEvent(EVENT_ID, "Concert"));
                verify(mockEventRepo, never()).save(any());
        }

        @Test
        public void GivenValidRequest_WhenCancelEventAndRefund_ThenEventStateIsCanceled() {
                setupStateBasedHappyPath();

                eventService.cancelEventAndRefund(OWNER_TOKEN, EVENT_ID);

                assertTrue(event.getStatus() == EventStatus.CANCELED);
        }

        // Catalog now owns only the cancellation + hand-off: on a successful cancel it must publish an
        // EventCancelledEvent so the sales listener runs the refund/ticket-void/notification flow. The
        // refund/ticket/notification BEHAVIOUR is asserted in EventCancellationRefundServiceTest.
        @Test
        public void GivenValidRequest_WhenCancelEventAndRefund_ThenEventCancelledEventIsPublished() {
                setupStateBasedHappyPath();

                eventService.cancelEventAndRefund(OWNER_TOKEN, EVENT_ID);

                verify(eventPublisher).publishEvent(new EventCancelledEvent(EVENT_ID, "Concert"));
        }

        // -- UC-19: owner publishes event (SCHEDULED -> ON_SALE) -------------

        @Test
        public void GivenScheduledEvent_WhenPublishEvent_ThenStatusOnSaleAndSaved() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);

                eventService.publishEvent(OWNER_TOKEN, COMPANY_ID, EVENT_ID);

                assertEquals(EventStatus.ON_SALE, event.getStatus());
                verify(mockEventRepo).save(event);
        }

        @Test
        public void GivenInvalidToken_WhenPublishEvent_ThenThrows() {
                when(sessionManager.validateToken(INVALID_TOKEN)).thenReturn(false);

                assertThrows(RuntimeException.class,
                                () -> eventService.publishEvent(INVALID_TOKEN, COMPANY_ID, EVENT_ID));
        }

        @Test
        public void GivenEventNotOwnedByCompany_WhenPublishEvent_ThenThrows() {
                int otherCompanyId = 999;
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event); // event.companyId == COMPANY_ID

                assertThrows(RuntimeException.class,
                                () -> eventService.publishEvent(OWNER_TOKEN, otherCompanyId, EVENT_ID));
                assertEquals(EventStatus.SCHEDULED, event.getStatus());
        }

        @Test
        public void GivenUserWithoutPermission_WhenPublishEvent_ThenThrows() {
                User noPermissionUser = new User(MANAGER_ID, "No Perm", "noperm@example.com", "hashedpassword", 30);
                when(sessionManager.validateToken(MANAGER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(MANAGER_TOKEN)).thenReturn(MANAGER_ID);
                when(userRepository.getUserById(MANAGER_ID)).thenReturn(noPermissionUser);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);

                assertThrows(RuntimeException.class,
                                () -> eventService.publishEvent(MANAGER_TOKEN, COMPANY_ID, EVENT_ID));
                assertEquals(EventStatus.SCHEDULED, event.getStatus());
        }

        @Test
        public void GivenDraftEvent_WhenPublishEvent_ThenThrowsInvalidStateTransition() {
                Event draftEvent = new Event(
                                EVENT_ID, "Concert", 4.5, List.of("Artist1"), EventCategory.CONCERT, COMPANY_ID,
                                EventStatus.DRAFT, venueMap,
                                List.of(new ShowDate(LocalDateTime.now().plusDays(10),
                                                LocalDateTime.now().plusDays(10).plusHours(2))),
                                ZoneId.of("Asia/Jerusalem"),
                                null, new DiscountPolicy(0));
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(draftEvent);

                assertThrows(InvalidStateTransitionException.class,
                                () -> eventService.publishEvent(OWNER_TOKEN, COMPANY_ID, EVENT_ID));
                assertEquals(EventStatus.DRAFT, draftEvent.getStatus());
        }

        // new test
        @Test
        void GivenSeatedVenueConfig_WhenConfigureVenueMap_ThenSeatCoordinatesArePreserved() {
                // configure seats A1 with x=10, y=20
                // fetch event venue map
                // assert A1.getX() == 10
                // assert A1.getY() == 20

        }

        @Test
        void GivenEventCreatedByAddEventAndVenueConfigured_WhenReserveStandingTicket_ThenReservationSucceeds() {
                // addEvent(...)
                // configureVenueMap(...)
                // reserveStandingTicketsForMember(...)
                // should not throw UnsupportedOperationException from PurchasePolicy

        }

        // -------------------------------------------------------------------------
        // addEvent: a new show must start after now at the venue (UC-19, #74, ADR-008)
        // -------------------------------------------------------------------------

        @Test
        void GivenShowStartingAt19LocalInJerusalem_WhenAddEventAt1930Local_ThenRejectedAsPast() {
                stubOwnerCanAddEvents();
                EventManagementService service = serviceWith(Clock.fixed(JERUSALEM_1930, ZoneOffset.UTC));

                IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.addEvent(
                                OWNER_TOKEN, creationRequest(LocalDateTime.of(2027, 7, 15, 19, 0), JERUSALEM)));

                assertEquals("Start time must be in the future", ex.getMessage());
                verify(mockEventRepo, never()).save(any());
        }

        @Test
        void GivenShowStartingAt20LocalInJerusalem_WhenAddEventAt1930Local_ThenCreatedInThatZone() {
                stubOwnerCanAddEvents();
                EventManagementService service = serviceWith(Clock.fixed(JERUSALEM_1930, ZoneOffset.UTC));

                EventDetailDTO created = service.addEvent(
                                OWNER_TOKEN, creationRequest(LocalDateTime.of(2027, 7, 15, 20, 0), JERUSALEM));

                assertEquals(JERUSALEM, created.timeZone());
                verify(mockEventRepo).save(any());
        }

        // The same 19:00 rejected in Jerusalem is still ahead at a venue on UTC (19:00Z > 16:30Z).
        @Test
        void GivenSameWallTimeAtAUtcVenue_WhenAddEventAt1630Utc_ThenCreated() {
                stubOwnerCanAddEvents();
                EventManagementService service = serviceWith(Clock.fixed(JERUSALEM_1930, ZoneOffset.UTC));

                EventDetailDTO created = service.addEvent(
                                OWNER_TOKEN, creationRequest(LocalDateTime.of(2027, 7, 15, 19, 0), ZoneOffset.UTC));

                assertEquals(ZoneOffset.UTC, created.timeZone());
        }

        @Test
        void GivenPastShowInUpdate_WhenEditEventDetails_ThenRejectedAndNotSaved() {
                stubOwnerCanAddEvents();
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                EventManagementService service = serviceWith(Clock.fixed(JERUSALEM_1930, ZoneOffset.UTC));
                ShowDateDTO past = new ShowDateDTO(LocalDateTime.of(2027, 7, 15, 19, 0), LocalDateTime.of(2027, 7, 15, 22, 0));

                assertThrows(IllegalArgumentException.class, () -> service.editEventDetails(OWNER_TOKEN,
                                new EventUpdateDTO(String.valueOf(EVENT_ID), null, null, null, null, null, List.of(past))));

                verify(mockEventRepo, never()).save(any());
        }

        private void stubOwnerCanAddEvents() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockEventRepo.nextId()).thenReturn(EVENT_ID + 1);
                when(mockEventRepo.nextVenueMapId()).thenReturn(2);
        }

        private EventCreationDTO creationRequest(LocalDateTime start, ZoneId timeZone) {
                return new EventCreationDTO(COMPANY_ID, "Gala", "desc", List.of("Artist1"), EventCategory.CONCERT,
                                4.5, LOCATION, List.of(new ShowDate(start, start.plusHours(3))), timeZone, null);
        }

        @Test
        @Disabled("UC-19: Owner adds event — DRAFT state initially")
        void givenOwner_whenAddEvent_thenEventInDraft() {
        }

        @Test
        @Disabled("UC-19: Manager without permission rejected")
        void givenManagerWithoutPermission_whenAddEvent_thenRejected() {
        }

        @Test
        @Disabled("UC-21: setEventPolicies stores PurchasePolicy + DiscountPolicy")
        void givenOwner_whenSetEventPolicies_thenStored() {
        }

        // -------------------------------------------------------------------------
        // getEventDetail
        // -------------------------------------------------------------------------

        @Test
        void GivenOwnerToken_WhenGetEventDetail_ThenReturnsDTOWithCorrectFields() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);

                EventDetailDTO result = eventService.getEventDetail(OWNER_TOKEN, EVENT_ID);

                assertEquals(String.valueOf(EVENT_ID), result.eventId());
                assertEquals("Concert", result.name());
                assertEquals(EventCategory.CONCERT, result.category());
                assertEquals(String.valueOf(COMPANY_ID), result.companyId());
                assertEquals(COMPANY_1_NAME, result.companyName());
                assertEquals(EventStatus.SCHEDULED, result.status());
                assertEquals(LOCATION, result.location());
        }

        @Test
        void GivenManagerWithPermission_WhenGetEventDetail_ThenReturnsDTOSuccessfully() {
                when(sessionManager.validateToken(MANAGER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(MANAGER_TOKEN)).thenReturn(MANAGER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(userRepository.getUserById(MANAGER_ID)).thenReturn(managerUser);

                EventDetailDTO result = eventService.getEventDetail(MANAGER_TOKEN, EVENT_ID);

                assertEquals(String.valueOf(EVENT_ID), result.eventId());
                assertEquals("Concert", result.name());
        }

        @Test
        void GivenInvalidToken_WhenGetEventDetail_ThenThrows() {
                when(sessionManager.validateToken(INVALID_TOKEN)).thenReturn(false);

                assertThrows(RuntimeException.class,
                                () -> eventService.getEventDetail(INVALID_TOKEN, EVENT_ID));
        }

        @Test
        void GivenEventNotFound_WhenGetEventDetail_ThenThrows() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenThrow(new RuntimeException("Event not found"));

                assertThrows(RuntimeException.class,
                                () -> eventService.getEventDetail(OWNER_TOKEN, EVENT_ID));
        }

        @Test
        void GivenUserNotInCompany_WhenGetEventDetail_ThenThrows() {
                User stranger = new User(99, "Stranger", "stranger@test.com", "hashedpw", 25);
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(99);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(userRepository.getUserById(99)).thenReturn(stranger);

                assertThrows(RuntimeException.class,
                                () -> eventService.getEventDetail(OWNER_TOKEN, EVENT_ID));
        }

        @Test
        void GivenEventWithNullVenueMap_WhenGetEventDetail_ThenLocationIsNull() {
                Event draftNoVenue = new Event(
                                EVENT_ID, "Draft Concert", 4.5, List.of("Artist1"),
                                EventCategory.MUSIC, COMPANY_ID, EventStatus.DRAFT, null,
                                List.of(new ShowDate(LocalDateTime.now().plusDays(10),
                                                LocalDateTime.now().plusDays(10).plusHours(2))),
                                ZoneId.of("Asia/Jerusalem"),
                                null, new DiscountPolicy(0));
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(draftNoVenue);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);

                EventDetailDTO result = eventService.getEventDetail(OWNER_TOKEN, EVENT_ID);

                assertNull(result.location());
        }

        // -------------------------------------------------------------------------
        // editEventDetails
        // -------------------------------------------------------------------------

        @Test
        void GivenOwnerAndNewName_WhenEditEventDetails_ThenNameIsUpdated() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);

                eventService.editEventDetails(OWNER_TOKEN,
                                new EventUpdateDTO(String.valueOf(EVENT_ID), "New Concert Name", null, null, null,
                                                null,
                                                null));

                assertEquals("New Concert Name", event.getName());
        }

        @Test
        void GivenOwnerAndNewTimeZone_WhenEditEventDetails_ThenTheEventIsInThatZone() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);

                eventService.editEventDetails(OWNER_TOKEN, new EventUpdateDTO(String.valueOf(EVENT_ID), null, null,
                                null, null, ZoneId.of("Europe/London"), null));

                assertEquals(ZoneId.of("Europe/London"), event.getTimeZone());
        }

        @Test
        void GivenOwnerAndNewCategory_WhenEditEventDetails_ThenCategoryIsUpdated() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);

                eventService.editEventDetails(OWNER_TOKEN,
                                new EventUpdateDTO(String.valueOf(EVENT_ID), null, null, "MUSIC", null, null, null));

                assertEquals(EventCategory.MUSIC, event.getCategory());
        }

        // Under a Turkish default locale "music".toUpperCase() is "MUS\u0130C", with a dotted capital I,
        // which names no category (#187).
        @Test
        void GivenATurkishDefaultLocale_WhenEditEventDetailsWithALowercaseCategory_ThenCategoryIsUpdated() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);
                java.util.Locale defaultLocale = java.util.Locale.getDefault();
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
                try {
                        eventService.editEventDetails(OWNER_TOKEN,
                                        new EventUpdateDTO(String.valueOf(EVENT_ID), null, null, "music", null, null, null));
                } finally {
                        java.util.Locale.setDefault(defaultLocale);
                }

                assertEquals(EventCategory.MUSIC, event.getCategory());
        }

        @Test
        void GivenBothFieldsNull_WhenEditEventDetails_ThenNothingChanges() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);

                eventService.editEventDetails(OWNER_TOKEN,
                                new EventUpdateDTO(String.valueOf(EVENT_ID), null, null, null, null, null, null));

                assertEquals("Concert", event.getName());
                assertEquals(EventCategory.CONCERT, event.getCategory());
        }

        @Test
        void GivenInventoryManager_WhenEditEventDetails_ThenSucceeds() {
                // Editing event metadata requires MANAGE_INVENTORY (not CONFIGURE_VENUE).
                when(sessionManager.validateToken(MANAGE_INVENTORY_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(MANAGE_INVENTORY_TOKEN)).thenReturn(INVENTORY_MANAGER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(userRepository.getUserById(INVENTORY_MANAGER_ID)).thenReturn(inventoryManagerUser);

                eventService.editEventDetails(MANAGE_INVENTORY_TOKEN,
                                new EventUpdateDTO(String.valueOf(EVENT_ID), "Manager Edited", null, null, null, null, null));

                assertEquals("Manager Edited", event.getName());
        }

        // -------------------------------------------------------------------------
        // Manager permission boundaries — MANAGE_INVENTORY (events) vs CONFIGURE_VENUE (venue)
        // -------------------------------------------------------------------------

        @Test
        void GivenConfigureVenueManager_WhenEditEventDetails_ThenThrows() {
                // A venue-only manager must NOT be able to edit event metadata.
                when(sessionManager.validateToken(MANAGER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(MANAGER_TOKEN)).thenReturn(MANAGER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(userRepository.getUserById(MANAGER_ID)).thenReturn(managerUser);

                assertThrows(RuntimeException.class, () -> eventService.editEventDetails(MANAGER_TOKEN,
                                new EventUpdateDTO(String.valueOf(EVENT_ID), "Nope", null, null, null, null, null)));
                assertEquals("Concert", event.getName());
        }

        @Test
        void GivenInventoryManager_WhenChangeEventStatus_ThenSucceeds() {
                when(sessionManager.validateToken(MANAGE_INVENTORY_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(MANAGE_INVENTORY_TOKEN)).thenReturn(INVENTORY_MANAGER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(userRepository.getUserById(INVENTORY_MANAGER_ID)).thenReturn(inventoryManagerUser);

                eventService.changeEventStatus(MANAGE_INVENTORY_TOKEN, EVENT_ID, EventStatus.ON_SALE);

                assertEquals(EventStatus.ON_SALE, event.getStatus());
        }

        @Test
        void GivenConfigureVenueManager_WhenChangeEventStatus_ThenThrows() {
                when(sessionManager.validateToken(MANAGER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(MANAGER_TOKEN)).thenReturn(MANAGER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(userRepository.getUserById(MANAGER_ID)).thenReturn(managerUser);

                assertThrows(RuntimeException.class,
                                () -> eventService.changeEventStatus(MANAGER_TOKEN, EVENT_ID, EventStatus.ON_SALE));
                assertEquals(EventStatus.SCHEDULED, event.getStatus());
        }

        @Test
        void GivenInventoryManager_WhenCancelEventAndRefund_ThenEventIsCanceled() {
                when(sessionManager.validateToken(MANAGE_INVENTORY_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(MANAGE_INVENTORY_TOKEN)).thenReturn(INVENTORY_MANAGER_ID);
                when(userRepository.getUserById(INVENTORY_MANAGER_ID)).thenReturn(inventoryManagerUser);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);

                eventService.cancelEventAndRefund(MANAGE_INVENTORY_TOKEN, EVENT_ID);

                assertEquals(EventStatus.CANCELED, event.getStatus());
        }

        @Test
        void GivenInventoryManager_WhenConfigureVenueMap_ThenThrows() {
                // MANAGE_INVENTORY does NOT grant venue editing (that's CONFIGURE_VENUE).
                when(sessionManager.validateToken(MANAGE_INVENTORY_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(MANAGE_INVENTORY_TOKEN)).thenReturn(INVENTORY_MANAGER_ID);
                when(userRepository.getUserById(INVENTORY_MANAGER_ID)).thenReturn(inventoryManagerUser);

                VenueMapConfigDTO config = new VenueMapConfigDTO(String.valueOf(EVENT_ID), "Venue", 1, 1, List.of());
                assertThrows(RuntimeException.class,
                                () -> eventService.configureVenueMap(MANAGE_INVENTORY_TOKEN, COMPANY_ID, config));
        }

        @Test
        void GivenInvalidToken_WhenEditEventDetails_ThenThrows() {
                when(sessionManager.validateToken(INVALID_TOKEN)).thenReturn(false);

                assertThrows(RuntimeException.class, () -> eventService.editEventDetails(INVALID_TOKEN,
                                new EventUpdateDTO(String.valueOf(EVENT_ID), "New Name", null, null, null, null, null)));
        }

        @Test
        void GivenEventNotFound_WhenEditEventDetails_ThenThrows() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenThrow(new RuntimeException("Event not found"));

                assertThrows(RuntimeException.class, () -> eventService.editEventDetails(OWNER_TOKEN,
                                new EventUpdateDTO(String.valueOf(EVENT_ID), "New Name", null, null, null, null, null)));
        }

        @Test
        void GivenNonNumericEventId_WhenEditEventDetails_ThenThrowsIllegalArgumentException() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);

                assertThrows(IllegalArgumentException.class, () -> eventService.editEventDetails(OWNER_TOKEN,
                                new EventUpdateDTO("not-a-number", "New Name", null, null, null, null, null)));
        }

        @Test
        void GivenUserNotInCompany_WhenEditEventDetails_ThenThrows() {
                User stranger = new User(99, "Stranger", "stranger@test.com", "hashedpw", 25);
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(99);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(userRepository.getUserById(99)).thenReturn(stranger);

                assertThrows(RuntimeException.class, () -> eventService.editEventDetails(OWNER_TOKEN,
                                new EventUpdateDTO(String.valueOf(EVENT_ID), "New Name", null, null, null, null, null)));
        }

        @Test
        void GivenEventOnSale_WhenEditEventDetails_ThenThrows() {
                Event onSaleEvent = new Event(
                                EVENT_ID, "Concert", 4.5, List.of("Artist1"),
                                EventCategory.CONCERT, COMPANY_ID, EventStatus.ON_SALE, venueMap,
                                List.of(new ShowDate(LocalDateTime.now().plusDays(10),
                                                LocalDateTime.now().plusDays(10).plusHours(2))),
                                ZoneId.of("Asia/Jerusalem"),
                                null, new DiscountPolicy(0));
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(onSaleEvent);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);

                assertThrows(RuntimeException.class, () -> eventService.editEventDetails(OWNER_TOKEN,
                                new EventUpdateDTO(String.valueOf(EVENT_ID), "New Name", null, null, null, null, null)));
        }

        @Test
        void GivenUnknownCategory_WhenEditEventDetails_ThenThrowsIllegalArgumentException() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);

                assertThrows(IllegalArgumentException.class, () -> eventService.editEventDetails(OWNER_TOKEN,
                                new EventUpdateDTO(String.valueOf(EVENT_ID), null, null, "INVALID_CATEGORY", null,
                                                null,
                                                null)));
        }

        @Test
        void GivenEventInDraftState_WhenEditEventDetails_ThenSucceeds() {
                Event draftEvent = new Event(
                                EVENT_ID, "Draft Concert", 4.5, List.of("Artist1"),
                                EventCategory.CONCERT, COMPANY_ID, EventStatus.DRAFT, venueMap,
                                List.of(new ShowDate(LocalDateTime.now().plusDays(10),
                                                LocalDateTime.now().plusDays(10).plusHours(2))),
                                ZoneId.of("Asia/Jerusalem"),
                                null, new DiscountPolicy(0));
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(draftEvent);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);

                eventService.editEventDetails(OWNER_TOKEN,
                                new EventUpdateDTO(String.valueOf(EVENT_ID), "Updated Draft Name", null, null, null,
                                                null,
                                                null));

                assertEquals("Updated Draft Name", draftEvent.getName());
        }

        @Test
        void GivenOwnerAndNewDescription_WhenEditEventDetails_ThenDescriptionIsUpdated() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);

                eventService.editEventDetails(OWNER_TOKEN,
                                new EventUpdateDTO(String.valueOf(EVENT_ID), null, "A brand new description", null,
                                                null, null, null));

                assertEquals("A brand new description", event.getDescription());
        }

        @Test
        void GivenOwnerAndNewLocation_WhenEditEventDetails_ThenVenueMapLocationIsUpdated() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);

                eventService.editEventDetails(OWNER_TOKEN,
                                new EventUpdateDTO(String.valueOf(EVENT_ID), null, null, null,
                                                new LocationDTO("France", "Paris"), null, null));

                assertEquals(new Location("France", "Paris"), event.getVenueMap().getLocation());
        }

        @Test
        void GivenOwnerAndNewShowDates_WhenEditEventDetails_ThenShowDatesAreReplaced() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);

                LocalDateTime newStart = LocalDateTime.now().plusDays(30);
                LocalDateTime newEnd = newStart.plusHours(3);
                eventService.editEventDetails(OWNER_TOKEN,
                                new EventUpdateDTO(String.valueOf(EVENT_ID), null, null, null, null,
                                                null,
                                                List.of(new ShowDateDTO(newStart, newEnd))));

                assertEquals(1, event.getShowDates().size());
                assertEquals(newStart, event.getShowDates().get(0).getStartTime());
                assertEquals(newEnd, event.getShowDates().get(0).getEndTime());
        }

        @Test
        void GivenDescribedEvent_WhenGetEventDetail_ThenReturnsDescription() {
                Event describedEvent = new Event(
                                EVENT_ID, "Concert", "The headline show of the year", 4.5, List.of("Artist1"),
                                EventCategory.CONCERT, COMPANY_ID, EventStatus.SCHEDULED, venueMap,
                                List.of(new ShowDate(LocalDateTime.now().plusDays(10),
                                                LocalDateTime.now().plusDays(10).plusHours(2))),
                                ZoneId.of("Asia/Jerusalem"),
                                null, new DiscountPolicy(0));
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(describedEvent);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);

                EventDetailDTO result = eventService.getEventDetail(OWNER_TOKEN, EVENT_ID);

                assertEquals("The headline show of the year", result.description());
        }

        ////////////////////////////////////////////////////// Tests for
        ////////////////////////////////////////////////////// listEventsForCompany
        @Test
        void GivenOwnerAndCompanyEvents_WhenListEventsForCompany_ThenReturnsMappedEvents() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockEventRepo.searchByCompanyAll(eq(COMPANY_ID), any())).thenReturn(List.of(event));

                List<EventDetailDTO> result = eventService.listEventsForCompany(OWNER_TOKEN, COMPANY_ID);

                assertNotNull(result);
                assertEquals(1, result.size());

                EventDetailDTO dto = result.get(0);

                assertEquals(String.valueOf(EVENT_ID), dto.eventId());
                assertEquals("Concert", dto.name());
                assertEquals(4.5, dto.rating());
                assertEquals(EventCategory.CONCERT, dto.category());
                assertEquals(LOCATION, dto.location());
                assertEquals(String.valueOf(COMPANY_ID), dto.companyId());
                assertEquals(COMPANY_1_NAME, dto.companyName());
                assertEquals(EventStatus.SCHEDULED, dto.status());
                assertEquals(event.getShowDates(), dto.showDates());
        }

        @Test
        void GivenOwnerAndNoEvents_WhenListEventsForCompany_ThenReturnsEmptyList() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockEventRepo.searchByCompanyAll(eq(COMPANY_ID), any())).thenReturn(List.of());

                List<EventDetailDTO> result = eventService.listEventsForCompany(OWNER_TOKEN, COMPANY_ID);

                assertNotNull(result);
                assertTrue(result.isEmpty());
        }

        @Test
        void GivenInvalidToken_WhenListEventsForCompany_ThenThrowsException() {
                when(sessionManager.validateToken(INVALID_TOKEN)).thenReturn(false);

                assertThrows(RuntimeException.class,
                                () -> eventService.listEventsForCompany(INVALID_TOKEN, COMPANY_ID));
        }

        @Test
        void GivenManagerMember_WhenListEventsForCompany_ThenReturnsEvents() {
                // The events list is a read-only hub: any active member may see it (managerUser holds
                // CONFIGURE_VENUE, not MANAGE_INVENTORY), so a venue/sales manager can reach the
                // per-event venue editor. Per-event mutations stay gated by their own permission.
                when(sessionManager.validateToken(MANAGER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(MANAGER_TOKEN)).thenReturn(MANAGER_ID);
                when(userRepository.getUserById(MANAGER_ID)).thenReturn(managerUser);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockEventRepo.searchByCompanyAll(eq(COMPANY_ID), any())).thenReturn(List.of(event));

                List<EventDetailDTO> result = eventService.listEventsForCompany(MANAGER_TOKEN, COMPANY_ID);

                assertNotNull(result);
                assertEquals(1, result.size());
                assertEquals(String.valueOf(EVENT_ID), result.get(0).eventId());
        }

        @Test
        void GivenNonMember_WhenListEventsForCompany_ThenThrowsException() {
                User stranger = new User(999, "Stranger", "stranger@example.com", "hashedpassword", 30);
                when(sessionManager.validateToken("stranger-token")).thenReturn(true);
                when(sessionManager.extractUserId("stranger-token")).thenReturn(999);
                when(userRepository.getUserById(999)).thenReturn(stranger);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);

                assertThrows(RuntimeException.class,
                                () -> eventService.listEventsForCompany("stranger-token", COMPANY_ID));
        }

        @Test
        void GivenManagerWithConfigureVenuePermission_WhenGetEventZones_ThenReturnsStandingZone() {
                when(sessionManager.validateToken(MANAGER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(MANAGER_TOKEN)).thenReturn(MANAGER_ID);
                when(userRepository.getUserById(MANAGER_ID)).thenReturn(managerUser);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);

                VenueLayoutDTO layout = eventService.getEventZones(MANAGER_TOKEN, EVENT_ID);
                List<ZoneDetailDTO> result = layout.zones();

                assertNotNull(result);
                assertEquals(1, result.size());

                ZoneDetailDTO dto = result.get(0);

                assertEquals("VIP", dto.name());
                assertFalse(dto.seated());
                assertEquals(0, dto.rows());
                assertEquals(0, dto.seatsPerRow());
                assertEquals(10, dto.capacity());
                assertEquals(100, dto.price());
        }

        @Test
        void GivenOwner_WhenGetEventZones_ThenReturnsStandingZone() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);

                VenueLayoutDTO layout = eventService.getEventZones(OWNER_TOKEN, EVENT_ID);
                List<ZoneDetailDTO> result = layout.zones();

                assertNotNull(result);
                assertEquals(1, result.size());

                ZoneDetailDTO dto = result.get(0);

                assertEquals("VIP", dto.name());
                assertFalse(dto.seated());
                assertEquals(0, dto.rows());
                assertEquals(0, dto.seatsPerRow());
                assertEquals(10, dto.capacity());
                assertEquals(100, dto.price());
        }

        @Test
        void GivenEventWithoutVenueMap_WhenGetEventZones_ThenReturnsEmptyList() {
                Event eventWithoutVenueMap = new Event(
                                EVENT_ID,
                                "Concert",
                                4.5,
                                List.of("Artist1"),
                                EventCategory.CONCERT,
                                COMPANY_ID,
                                EventStatus.SCHEDULED,
                                null,
                                event.getShowDates(),
                                ZoneId.of("Asia/Jerusalem"),
                                null,
                                new DiscountPolicy(0)

                );

                when(sessionManager.validateToken(MANAGER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(MANAGER_TOKEN)).thenReturn(MANAGER_ID);
                when(userRepository.getUserById(MANAGER_ID)).thenReturn(managerUser);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(eventWithoutVenueMap);

                VenueLayoutDTO layout = eventService.getEventZones(MANAGER_TOKEN, EVENT_ID);
                List<ZoneDetailDTO> result = layout.zones();

                assertNotNull(result);
                assertTrue(result.isEmpty());
        }

        @Test
        void GivenEventWithEmptyVenueMap_WhenGetEventZones_ThenReturnsEmptyList() {
                VenueMap emptyVenueMap = new VenueMap(1, LOCATION, List.of());

                Event eventWithEmptyVenueMap = new Event(
                                EVENT_ID,
                                "Concert",
                                4.5,
                                List.of("Artist1"),
                                EventCategory.CONCERT,
                                COMPANY_ID,
                                EventStatus.SCHEDULED,
                                emptyVenueMap,
                                event.getShowDates(),
                                ZoneId.of("Asia/Jerusalem"),
                                null,
                                new DiscountPolicy(0));

                when(sessionManager.validateToken(MANAGER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(MANAGER_TOKEN)).thenReturn(MANAGER_ID);
                when(userRepository.getUserById(MANAGER_ID)).thenReturn(managerUser);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(eventWithEmptyVenueMap);

                VenueLayoutDTO layout = eventService.getEventZones(MANAGER_TOKEN, EVENT_ID);
                List<ZoneDetailDTO> result = layout.zones();

                assertNotNull(result);
                assertTrue(result.isEmpty());
        }

        @Test
        void GivenSeatedZoneWithSeats_WhenGetEventZones_ThenReturnsRowsAndSeatsPerRow() {
                Seat seatA1 = new Seat("A1", 0, 0);
                Seat seatA2 = new Seat("A2", 1, 0);
                Seat seatB1 = new Seat("B1", 0, 1);
                Seat seatB2 = new Seat("B2", 1, 1);

                SeatedZone seatedZone = new SeatedZone(
                                ZONE_ID,
                                "Seated VIP",
                                250,
                                List.of(seatA1, seatA2, seatB1, seatB2));

                VenueMap seatedVenueMap = new VenueMap(1, LOCATION, List.of(seatedZone));

                Event seatedEvent = new Event(
                                EVENT_ID,
                                "Concert",
                                4.5,
                                List.of("Artist1"),
                                EventCategory.CONCERT,
                                COMPANY_ID,
                                EventStatus.SCHEDULED,
                                seatedVenueMap,
                                event.getShowDates(),
                                ZoneId.of("Asia/Jerusalem"),
                                null,
                                new DiscountPolicy(0)

                );

                when(sessionManager.validateToken(MANAGER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(MANAGER_TOKEN)).thenReturn(MANAGER_ID);
                when(userRepository.getUserById(MANAGER_ID)).thenReturn(managerUser);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(seatedEvent);

                VenueLayoutDTO layout = eventService.getEventZones(MANAGER_TOKEN, EVENT_ID);
                List<ZoneDetailDTO> result = layout.zones();

                assertNotNull(result);
                assertEquals(1, result.size());

                ZoneDetailDTO dto = result.get(0);

                assertEquals("Seated VIP", dto.name());
                assertTrue(dto.seated());
                assertEquals(2, dto.rows());
                assertEquals(2, dto.seatsPerRow());
                assertEquals(0, dto.capacity());
                assertEquals(250, dto.price());
        }

        @Test
        void GivenSeatedZoneWithoutSeats_WhenGetEventZones_ThenRowsAndSeatsPerRowAreZero() {
                SeatedZone seatedZone = new SeatedZone(
                                ZONE_ID,
                                "Empty Seated Zone",
                                150,
                                List.of());

                VenueMap seatedVenueMap = new VenueMap(1, LOCATION, List.of(seatedZone));

                Event seatedEvent = new Event(
                                EVENT_ID,
                                "Concert",
                                4.5,
                                List.of("Artist1"),
                                EventCategory.CONCERT,
                                COMPANY_ID,
                                EventStatus.SCHEDULED,
                                seatedVenueMap,
                                event.getShowDates(),
                                ZoneId.of("Asia/Jerusalem"),
                                null,
                                new DiscountPolicy(0));

                when(sessionManager.validateToken(MANAGER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(MANAGER_TOKEN)).thenReturn(MANAGER_ID);
                when(userRepository.getUserById(MANAGER_ID)).thenReturn(managerUser);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(seatedEvent);

                VenueLayoutDTO layout = eventService.getEventZones(MANAGER_TOKEN, EVENT_ID);
                List<ZoneDetailDTO> result = layout.zones();

                assertNotNull(result);
                assertEquals(1, result.size());

                ZoneDetailDTO dto = result.get(0);

                assertEquals("Empty Seated Zone", dto.name());
                assertTrue(dto.seated());
                assertEquals(0, dto.rows());
                assertEquals(0, dto.seatsPerRow());
                assertEquals(0, dto.capacity());
                assertEquals(150, dto.price());
        }

        @Test
        void GivenMixedStandingAndSeatedZones_WhenGetEventZones_ThenReturnsAllZones() {
                Seat seatA1 = new Seat("A1", 0, 0);
                Seat seatA2 = new Seat("A2", 1, 0);

                SeatedZone seatedZone = new SeatedZone(
                                6,
                                "Regular Seats",
                                200,
                                List.of(seatA1, seatA2));

                StandingZone standingZone = new StandingZone(
                                7,
                                "Standing Area",
                                300,
                                80);

                VenueMap mixedVenueMap = new VenueMap(1, LOCATION, List.of(seatedZone, standingZone));

                Event mixedEvent = new Event(
                                EVENT_ID,
                                "Concert",
                                4.5,
                                List.of("Artist1"),
                                EventCategory.CONCERT,
                                COMPANY_ID,
                                EventStatus.SCHEDULED,
                                mixedVenueMap,
                                event.getShowDates(),
                                ZoneId.of("Asia/Jerusalem"),
                                null,
                                new DiscountPolicy(0)

                );

                when(sessionManager.validateToken(MANAGER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(MANAGER_TOKEN)).thenReturn(MANAGER_ID);
                when(userRepository.getUserById(MANAGER_ID)).thenReturn(managerUser);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(mixedEvent);

                VenueLayoutDTO layout = eventService.getEventZones(MANAGER_TOKEN, EVENT_ID);
                List<ZoneDetailDTO> result = layout.zones();

                assertNotNull(result);
                assertEquals(2, result.size());

                ZoneDetailDTO seatedDto = result.get(0);

                assertEquals("Regular Seats", seatedDto.name());
                assertTrue(seatedDto.seated());
                assertEquals(1, seatedDto.rows());
                assertEquals(2, seatedDto.seatsPerRow());
                assertEquals(0, seatedDto.capacity());
                assertEquals(200, seatedDto.price());

                ZoneDetailDTO standingDto = result.get(1);

                assertEquals("Standing Area", standingDto.name());
                assertFalse(standingDto.seated());
                assertEquals(0, standingDto.rows());
                assertEquals(0, standingDto.seatsPerRow());
                assertEquals(300, standingDto.capacity());
                assertEquals(80, standingDto.price());
        }

        @Test
        void GivenInvalidToken_WhenGetEventZones_ThenThrowsException() {
                when(sessionManager.validateToken(INVALID_TOKEN)).thenReturn(false);

                assertThrows(RuntimeException.class,
                                () -> eventService.getEventZones(INVALID_TOKEN, EVENT_ID));
        }

        @Test
        void GivenValidTokenButUserNotFound_WhenListEventsForCompany_ThenThrowsException() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(null);

                assertThrows(RuntimeException.class,
                                () -> eventService.listEventsForCompany(OWNER_TOKEN, COMPANY_ID));
        }

        @Test
        void GivenCompanyNotFound_WhenListEventsForCompany_ThenThrowsException() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenThrow(new CompanyNotFoundException(COMPANY_ID));
                when(mockEventRepo.findByCompanyId(COMPANY_ID)).thenReturn(List.of(event));

                assertThrows(CompanyNotFoundException.class,
                                () -> eventService.listEventsForCompany(OWNER_TOKEN, COMPANY_ID));
        }

        //////////////////////////////////////// Tests for getEventZones
        @Test
        void GivenEventNotFound_WhenGetEventZones_ThenThrowsException() {
                when(sessionManager.validateToken(MANAGER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(MANAGER_TOKEN)).thenReturn(MANAGER_ID);
                when(userRepository.getUserById(MANAGER_ID)).thenReturn(managerUser);
                when(mockEventRepo.findById(EVENT_ID)).thenThrow(new EventNotFoundException(EVENT_ID));

                assertThrows(EventNotFoundException.class,
                                () -> eventService.getEventZones(MANAGER_TOKEN, EVENT_ID));
        }

        @Test
        void GivenValidTokenButUserNotFound_WhenGetEventZones_ThenThrowsException() {
                when(sessionManager.validateToken(MANAGER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(MANAGER_TOKEN)).thenReturn(MANAGER_ID);
                when(userRepository.getUserById(MANAGER_ID)).thenReturn(null);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);

                assertThrows(RuntimeException.class,
                                () -> eventService.getEventZones(MANAGER_TOKEN, EVENT_ID));
        }

        @Test
        void GivenManagerWithoutConfigureVenuePermission_WhenGetEventZones_ThenThrowsException() {
                String LIMITED_MANAGER_TOKEN = "limited-manager-token";
                // Distinct id (INVENTORY_MANAGER_ID=3 is already seeded in setUp; appointments are now
                // keyed by userId in a shared repo, so reusing 3 would clash with that active appointment).
                int LIMITED_MANAGER_ID = 77;

                User limitedManager = new User(
                                LIMITED_MANAGER_ID,
                                "Limited Manager",
                                "limited@example.com",
                                "hashedpassword",
                                30);

                membershipService.receiveManagerAppointment(
                                LIMITED_MANAGER_ID,
                                COMPANY_ID,
                                OWNER_ID,
                                List.of(Permission.MANAGE_INVENTORY));
                membershipService.acceptInvitation(LIMITED_MANAGER_ID, COMPANY_ID);

                when(sessionManager.validateToken(LIMITED_MANAGER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(LIMITED_MANAGER_TOKEN)).thenReturn(LIMITED_MANAGER_ID);
                when(userRepository.getUserById(LIMITED_MANAGER_ID)).thenReturn(limitedManager);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);

                assertThrows(RuntimeException.class,
                                () -> eventService.getEventZones(LIMITED_MANAGER_TOKEN, EVENT_ID));
        }

        @Test
        void GivenSeatedZoneWithUnevenRows_WhenGetEventZones_ThenSeatsPerRowIsMaxOfAnyRow() {
                Seat seatA1 = new Seat("A1", 0, 0);
                Seat seatA2 = new Seat("A2", 1, 0);
                Seat seatA3 = new Seat("A3", 2, 0);
                Seat seatB1 = new Seat("B1", 0, 1);

                SeatedZone seatedZone = new SeatedZone(
                                ZONE_ID,
                                "Uneven Seated Zone",
                                250,
                                List.of(seatA1, seatA2, seatA3, seatB1));

                VenueMap seatedVenueMap = new VenueMap(1, LOCATION, List.of(seatedZone));

                Event seatedEvent = new Event(
                                EVENT_ID,
                                "Concert",
                                4.5,
                                List.of("Artist1"),
                                EventCategory.CONCERT,
                                COMPANY_ID,
                                EventStatus.SCHEDULED,
                                seatedVenueMap,
                                event.getShowDates(),
                                ZoneId.of("Asia/Jerusalem"),
                                null,
                                new DiscountPolicy(0)

                );

                when(sessionManager.validateToken(MANAGER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(MANAGER_TOKEN)).thenReturn(MANAGER_ID);
                when(userRepository.getUserById(MANAGER_ID)).thenReturn(managerUser);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(seatedEvent);

                VenueLayoutDTO layout = eventService.getEventZones(MANAGER_TOKEN, EVENT_ID);
                List<ZoneDetailDTO> result = layout.zones();

                assertNotNull(result);
                assertEquals(1, result.size());

                ZoneDetailDTO dto = result.get(0);

                assertEquals("Uneven Seated Zone", dto.name());
                assertTrue(dto.seated());
                assertEquals(2, dto.rows());
                // Rows are grouped by seat-label prefix: row "A" has 3 seats (A1,A2,A3),
                // row "B" has 1 (B1). seatsPerRow is the max of any row.
                assertEquals(3, dto.seatsPerRow());
                assertEquals(0, dto.capacity());
                assertEquals(250, dto.price());
        }

        // -- grid: getEventZones reflects saved grid + zone placement -----------

        @Test
        void GivenPlacedZoneOnCustomGrid_WhenGetEventZones_ThenReturnsGridSizeAndPlacement() {
                StandingZone gridZone = new StandingZone(ZONE_ID, "Grid VIP", 10, 100);
                VenueMap gridVenueMap = new VenueMap(1, LOCATION, List.of(gridZone), 4, 5);
                gridVenueMap.placeZoneOnGrid(ZONE_ID, 2, 3, 1, 2);

                Event gridEvent = new Event(
                                EVENT_ID, "Concert", 4.5, List.of("Artist1"), EventCategory.CONCERT, COMPANY_ID,
                                EventStatus.SCHEDULED, gridVenueMap, event.getShowDates(), ZoneId.of("Asia/Jerusalem"), null, new DiscountPolicy(0));

                when(sessionManager.validateToken(MANAGER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(MANAGER_TOKEN)).thenReturn(MANAGER_ID);
                when(userRepository.getUserById(MANAGER_ID)).thenReturn(managerUser);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(gridEvent);

                VenueLayoutDTO layout = eventService.getEventZones(MANAGER_TOKEN, EVENT_ID);

                assertEquals(4, layout.gridRows());
                assertEquals(5, layout.gridCols());
                assertEquals(1, layout.zones().size());

                GridPlacementDTO placement = layout.zones().get(0).placement();
                assertNotNull(placement);
                assertEquals(2, placement.row());
                assertEquals(3, placement.col());
                assertEquals(1, placement.rowSpan());
                assertEquals(2, placement.colSpan());
        }

        // -- grid: configureVenueMap rejects an overlapping placement -----------

        @Test
        void GivenOverlappingZonePlacements_WhenConfigureVenueMap_ThenThrows() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);

                VenueMapConfigDTO config = new VenueMapConfigDTO(
                                String.valueOf(EVENT_ID), "Test Venue", 3, 3,
                                List.of(
                                                new VenueMapConfigDTO.ZoneConfigDTO(
                                                                "Zone A", false, 100, null, 50.0,
                                                                new GridPlacementDTO(1, 1, 2, 2)),
                                                new VenueMapConfigDTO.ZoneConfigDTO(
                                                                "Zone B", false, 100, null, 50.0,
                                                                new GridPlacementDTO(1, 1, 1, 1))));

                assertThrows(IllegalStateException.class,
                                () -> eventService.configureVenueMap(OWNER_TOKEN, COMPANY_ID, config));
        }

        // -------------------------------------------------------------------------
        // #185: configureVenueMap enforces the venue size limits server-side. The editor's field
        // limits are advisory only, so a client can send any size. Small limits keep each case cheap;
        // VenueLimitsPropertiesTest pins the shipped values.
        // -------------------------------------------------------------------------

        private static final int LIMIT_GRID = 4;
        private static final int LIMIT_SEATS_PER_ZONE = 6;
        private static final int LIMIT_STANDING_CAPACITY = 50;
        private static final int LIMIT_SEATS_PER_MAP = 10;
        private static final VenueLimitsProperties SMALL_VENUE_LIMITS = new VenueLimitsProperties(
                        LIMIT_GRID, LIMIT_SEATS_PER_ZONE, LIMIT_STANDING_CAPACITY, LIMIT_SEATS_PER_MAP);

        @Test
        void GivenSeatedZoneOverTheSeatLimit_WhenConfigureVenueMap_ThenRejectedAndNothingSaved() {
                assertVenueRejected(venueConfig(LIMIT_GRID, LIMIT_GRID,
                                seatedZone("Orchestra", "A", LIMIT_SEATS_PER_ZONE + 1)));
        }

        @Test
        void GivenSeatedZoneAtTheSeatLimit_WhenConfigureVenueMap_ThenSaved() {
                assertVenueSaved(venueConfig(LIMIT_GRID, LIMIT_GRID,
                                seatedZone("Orchestra", "A", LIMIT_SEATS_PER_ZONE)), 1);
        }

        @Test
        void GivenStandingZoneOverTheCapacityLimit_WhenConfigureVenueMap_ThenRejectedAndNothingSaved() {
                assertVenueRejected(venueConfig(LIMIT_GRID, LIMIT_GRID,
                                standingZone("Floor", LIMIT_STANDING_CAPACITY + 1)));
        }

        // The standing limit is above the per-map seat limit, as in the shipped defaults: standing places
        // are not seats, so a standing zone at its own limit is accepted whatever the seat total allows.
        @Test
        void GivenStandingZoneAtTheCapacityLimit_WhenConfigureVenueMap_ThenSaved() {
                assertVenueSaved(venueConfig(LIMIT_GRID, LIMIT_GRID,
                                standingZone("Floor", LIMIT_STANDING_CAPACITY)), 1);
        }

        @Test
        void GivenGridRowsOverTheGridLimit_WhenConfigureVenueMap_ThenRejectedAndNothingSaved() {
                assertVenueRejected(venueConfig(LIMIT_GRID + 1, LIMIT_GRID, standingZone("Floor", 10)));
        }

        @Test
        void GivenGridColsOverTheGridLimit_WhenConfigureVenueMap_ThenRejectedAndNothingSaved() {
                assertVenueRejected(venueConfig(LIMIT_GRID, LIMIT_GRID + 1, standingZone("Floor", 10)));
        }

        @Test
        void GivenGridAtTheGridLimit_WhenConfigureVenueMap_ThenSaved() {
                assertVenueSaved(venueConfig(LIMIT_GRID, LIMIT_GRID, standingZone("Floor", 10)), 1);
        }

        @Test
        void GivenMoreZonesThanGridCells_WhenConfigureVenueMap_ThenRejectedAndNothingSaved() {
                assertVenueRejected(venueConfig(1, 2,
                                standingZone("Zone 1", 10), standingZone("Zone 2", 10), standingZone("Zone 3", 10)));
        }

        @Test
        void GivenAsManyZonesAsGridCells_WhenConfigureVenueMap_ThenSaved() {
                assertVenueSaved(venueConfig(1, 2, standingZone("Zone 1", 10), standingZone("Zone 2", 10)), 2);
        }

        @Test
        void GivenSeatTotalOverTheMapLimit_WhenConfigureVenueMap_ThenRejectedAndNothingSaved() {
                assertVenueRejected(venueConfig(LIMIT_GRID, LIMIT_GRID,
                                seatedZone("Orchestra", "A", LIMIT_SEATS_PER_ZONE),
                                seatedZone("Balcony", "B", LIMIT_SEATS_PER_MAP - LIMIT_SEATS_PER_ZONE + 1)));
        }

        @Test
        void GivenSeatTotalAtTheMapLimit_WhenConfigureVenueMap_ThenSaved() {
                assertVenueSaved(venueConfig(LIMIT_GRID, LIMIT_GRID,
                                seatedZone("Orchestra", "A", LIMIT_SEATS_PER_ZONE),
                                seatedZone("Balcony", "B", LIMIT_SEATS_PER_MAP - LIMIT_SEATS_PER_ZONE)), 2);
        }

        // addInventoryZone and addPlacesToStandingZone also size zones, so they apply the same per-zone caps.
        // The fixture's map holds one standing zone (ZONE_ID) of 10 places.

        @Test
        void GivenSeatedZoneOverTheSeatLimit_WhenAddInventoryZone_ThenRejectedAndNothingSaved() {
                EventManagementService service = ownerCanConfigureVenueUnder(SMALL_VENUE_LIMITS);

                assertThrows(IllegalArgumentException.class,
                                () -> service.addInventoryZone(OWNER_TOKEN, COMPANY_ID, EVENT_ID,
                                                seatedZone("Orchestra", "A", LIMIT_SEATS_PER_ZONE + 1)));
                verify(mockEventRepo, never()).save(any());
                assertEquals(1, event.getVenueMap().getInventoryZones().size(), "no zone may be added");
        }

        @Test
        void GivenStandingZoneOverTheCapacityLimit_WhenAddInventoryZone_ThenRejectedAndNothingSaved() {
                EventManagementService service = ownerCanConfigureVenueUnder(SMALL_VENUE_LIMITS);

                assertThrows(IllegalArgumentException.class,
                                () -> service.addInventoryZone(OWNER_TOKEN, COMPANY_ID, EVENT_ID,
                                                standingZone("Floor", LIMIT_STANDING_CAPACITY + 1)));
                verify(mockEventRepo, never()).save(any());
                assertEquals(1, event.getVenueMap().getInventoryZones().size(), "no zone may be added");
        }

        @Test
        void GivenZonesAtTheirLimits_WhenAddInventoryZone_ThenBothAdded() {
                EventManagementService service = ownerCanConfigureVenueUnder(SMALL_VENUE_LIMITS);

                service.addInventoryZone(OWNER_TOKEN, COMPANY_ID, EVENT_ID,
                                seatedZone("Orchestra", "A", LIMIT_SEATS_PER_ZONE));
                service.addInventoryZone(OWNER_TOKEN, COMPANY_ID, EVENT_ID,
                                standingZone("Floor", LIMIT_STANDING_CAPACITY));

                assertEquals(3, event.getVenueMap().getInventoryZones().size());
        }

        @Test
        void GivenPlacesThatTakeTheZoneOverTheCapacityLimit_WhenAddPlacesToStandingZone_ThenRejectedAndUnchanged() {
                EventManagementService service = ownerCanConfigureVenueUnder(SMALL_VENUE_LIMITS);
                int capacityBefore = zone.getCapacity();

                assertThrows(IllegalArgumentException.class,
                                () -> service.addPlacesToStandingZone(OWNER_TOKEN, COMPANY_ID, EVENT_ID, ZONE_ID,
                                                LIMIT_STANDING_CAPACITY - capacityBefore + 1));
                verify(mockEventRepo, never()).save(any());
                assertEquals(capacityBefore, zone.getCapacity(), "a refused call must leave the zone's capacity as it was");
        }

        @Test
        void GivenPlacesThatTakeTheZoneToTheCapacityLimit_WhenAddPlacesToStandingZone_ThenSaved() {
                EventManagementService service = ownerCanConfigureVenueUnder(SMALL_VENUE_LIMITS);

                service.addPlacesToStandingZone(OWNER_TOKEN, COMPANY_ID, EVENT_ID, ZONE_ID,
                                LIMIT_STANDING_CAPACITY - zone.getCapacity());

                verify(mockEventRepo).save(event);
                assertEquals(LIMIT_STANDING_CAPACITY, zone.getCapacity());
        }

        // Growing a seated zone, or the map, one call at a time must not get past the caps a whole map is held to.

        @Test
        void GivenSeatsThatTakeTheZoneOverTheSeatLimit_WhenAddSeatsToSeatedZone_ThenRejectedAndUnchanged() {
                EventManagementService service = ownerCanConfigureVenueUnder(SMALL_VENUE_LIMITS);
                int seatedZoneId = service.addInventoryZone(OWNER_TOKEN, COMPANY_ID, EVENT_ID,
                                seatedZone("Orchestra", "A", LIMIT_SEATS_PER_ZONE));
                clearInvocations(mockEventRepo);

                assertThrows(IllegalArgumentException.class,
                                () -> service.addSeatsToSeatedZone(OWNER_TOKEN, COMPANY_ID, EVENT_ID, seatedZoneId,
                                                List.of(new VenueMapConfigDTO.SeatConfigDTO("B1", 10.0, 1))));
                verify(mockEventRepo, never()).save(any());
                assertEquals(LIMIT_SEATS_PER_ZONE, seatCountOf(seatedZoneId), "a refused call must leave the zone's seats as they were");
        }

        @Test
        void GivenARowThatTakesTheZoneOverTheSeatLimit_WhenAddSeatRowToSeatedZone_ThenRejectedAndUnchanged() {
                EventManagementService service = ownerCanConfigureVenueUnder(SMALL_VENUE_LIMITS);
                int seatedZoneId = service.addInventoryZone(OWNER_TOKEN, COMPANY_ID, EVENT_ID,
                                seatedZone("Orchestra", "A", LIMIT_SEATS_PER_ZONE - 1));
                clearInvocations(mockEventRepo);

                assertThrows(IllegalArgumentException.class,
                                () -> service.addSeatRowToSeatedZone(OWNER_TOKEN, COMPANY_ID, EVENT_ID, seatedZoneId,
                                                "B", 1, 2, 0.0, 1.0, 10.0));
                verify(mockEventRepo, never()).save(any());
                assertEquals(LIMIT_SEATS_PER_ZONE - 1, seatCountOf(seatedZoneId));
        }

        @Test
        void GivenSeatsThatTakeTheZoneToTheSeatLimit_WhenAddSeatsToSeatedZone_ThenSaved() {
                EventManagementService service = ownerCanConfigureVenueUnder(SMALL_VENUE_LIMITS);
                int seatedZoneId = service.addInventoryZone(OWNER_TOKEN, COMPANY_ID, EVENT_ID,
                                seatedZone("Orchestra", "A", LIMIT_SEATS_PER_ZONE - 1));

                service.addSeatsToSeatedZone(OWNER_TOKEN, COMPANY_ID, EVENT_ID, seatedZoneId,
                                List.of(new VenueMapConfigDTO.SeatConfigDTO("B1", 10.0, 1)));

                assertEquals(LIMIT_SEATS_PER_ZONE, seatCountOf(seatedZoneId));
        }

        @Test
        void GivenAZoneThatTakesTheMapOverTheSeatLimit_WhenAddInventoryZone_ThenRejectedAndNothingSaved() {
                EventManagementService service = ownerCanConfigureVenueUnder(SMALL_VENUE_LIMITS);
                service.addInventoryZone(OWNER_TOKEN, COMPANY_ID, EVENT_ID,
                                seatedZone("Orchestra", "A", LIMIT_SEATS_PER_ZONE));
                clearInvocations(mockEventRepo);

                assertThrows(IllegalArgumentException.class,
                                () -> service.addInventoryZone(OWNER_TOKEN, COMPANY_ID, EVENT_ID,
                                                seatedZone("Balcony", "B", LIMIT_SEATS_PER_MAP - LIMIT_SEATS_PER_ZONE + 1)));
                verify(mockEventRepo, never()).save(any());
                assertEquals(2, event.getVenueMap().getInventoryZones().size(), "no zone may be added");
        }

        @Test
        void GivenSeatsThatTakeTheMapOverTheSeatLimit_WhenAddSeatsToSeatedZone_ThenRejected() {
                EventManagementService service = ownerCanConfigureVenueUnder(SMALL_VENUE_LIMITS);
                service.addInventoryZone(OWNER_TOKEN, COMPANY_ID, EVENT_ID,
                                seatedZone("Orchestra", "A", LIMIT_SEATS_PER_ZONE));
                int balconyId = service.addInventoryZone(OWNER_TOKEN, COMPANY_ID, EVENT_ID,
                                seatedZone("Balcony", "B", LIMIT_SEATS_PER_MAP - LIMIT_SEATS_PER_ZONE));
                clearInvocations(mockEventRepo);

                assertThrows(IllegalArgumentException.class,
                                () -> service.addSeatsToSeatedZone(OWNER_TOKEN, COMPANY_ID, EVENT_ID, balconyId,
                                                List.of(new VenueMapConfigDTO.SeatConfigDTO("C1", 10.0, 2))));
                verify(mockEventRepo, never()).save(any());
        }

        @Test
        void GivenAMapWhoseGridCellsAreAllTaken_WhenAddInventoryZone_ThenRejectedAndNothingSaved() {
                EventManagementService service = ownerCanConfigureVenueUnder(SMALL_VENUE_LIMITS);
                int gridCells = venueMap.getGridRows() * venueMap.getGridCols();
                for (int added = venueMap.getInventoryZones().size(); added < gridCells; added++) {
                        service.addInventoryZone(OWNER_TOKEN, COMPANY_ID, EVENT_ID, standingZone("Floor " + added, 1));
                }
                clearInvocations(mockEventRepo);

                assertThrows(IllegalArgumentException.class,
                                () -> service.addInventoryZone(OWNER_TOKEN, COMPANY_ID, EVENT_ID, standingZone("One too many", 1)));
                verify(mockEventRepo, never()).save(any());
                assertEquals(gridCells, event.getVenueMap().getInventoryZones().size(), "no zone may be added");
        }

        private int seatCountOf(int zoneId) {
                return event.getVenueMap().getZone(zoneId).getCapacity();
        }

        private void assertVenueRejected(VenueMapConfigDTO config) {
                EventManagementService service = ownerCanConfigureVenueUnder(SMALL_VENUE_LIMITS);

                assertThrows(IllegalArgumentException.class,
                                () -> service.configureVenueMap(OWNER_TOKEN, COMPANY_ID, config),
                                "a venue map over a size limit must be refused");
                verify(mockEventRepo, never()).save(any());
                assertSame(venueMap, event.getVenueMap(), "the event must keep its previous venue map");
        }

        private void assertVenueSaved(VenueMapConfigDTO config, int expectedZones) {
                EventManagementService service = ownerCanConfigureVenueUnder(SMALL_VENUE_LIMITS);

                service.configureVenueMap(OWNER_TOKEN, COMPANY_ID, config);

                verify(mockEventRepo).save(event);
                assertEquals(expectedZones, event.getVenueMap().getInventoryZones().size(),
                                "a venue map exactly at the limits must be stored whole");
        }

        private EventManagementService ownerCanConfigureVenueUnder(VenueLimitsProperties limits) {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                return serviceWith(Clock.fixed(VENUE_LIMITS_NOW, ZoneOffset.UTC), limits);
        }

        private VenueMapConfigDTO venueConfig(int gridRows, int gridCols, VenueMapConfigDTO.ZoneConfigDTO... zones) {
                return new VenueMapConfigDTO(String.valueOf(EVENT_ID), "Test Venue", gridRows, gridCols, List.of(zones));
        }

        private static VenueMapConfigDTO.ZoneConfigDTO standingZone(String name, int capacity) {
                return new VenueMapConfigDTO.ZoneConfigDTO(name, false, capacity, null, 50.0, null);
        }

        private static VenueMapConfigDTO.ZoneConfigDTO seatedZone(String name, String row, int seatCount) {
                List<VenueMapConfigDTO.SeatConfigDTO> seats = new ArrayList<>();
                IntStream.rangeClosed(1, seatCount).forEach(number ->
                                seats.add(new VenueMapConfigDTO.SeatConfigDTO(row + number, number * 10.0, 0)));
                return new VenueMapConfigDTO.ZoneConfigDTO(name, true, null, seats, 50.0, null);
        }

        // ---- the event purchase policy survives a read and a save back (#187) ------------------
        // The policy editor reads the policy as a DTO and saves that DTO back, so a policy the read
        // cannot express must be refused, never read as NONE, or saving it back erases it.

        private static Stream<PurchasePolicy> everyPurchasePolicyShape() {
                return Stream.of(
                                new NoPurchasePolicy(),
                                new AgePurchasePolicy(18),
                                new MinTicketsPurchasePolicy(2),
                                new MaxTicketsPurchasePolicy(6),
                                new AndPurchasePolicy(new AgePurchasePolicy(18), new MaxTicketsPurchasePolicy(6)),
                                new OrPurchasePolicy(new MinTicketsPurchasePolicy(2), new MaxTicketsPurchasePolicy(6)),
                                new AndPurchasePolicy(new AgePurchasePolicy(21),
                                                new OrPurchasePolicy(new MinTicketsPurchasePolicy(2),
                                                                new MaxTicketsPurchasePolicy(4))),
                                new OrPurchasePolicy(new AndPurchasePolicy(new AgePurchasePolicy(16),
                                                new MinTicketsPurchasePolicy(3)), new MaxTicketsPurchasePolicy(8)));
        }

        // A policy type the DTO mapping has never heard of.
        private static final class UnmappedPurchasePolicy implements PurchasePolicy {
                @Override
                public boolean isSatisfiedBy(PurchaseContext context) {
                        return true;
                }

                @Override
                public String getFailureMessage() {
                        return "";
                }
        }

        private void ownerCanEditTheEventPolicy(PurchasePolicy policy) {
                event.setPurchasePolicy(policy);
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
        }

        @ParameterizedTest
        @MethodSource("everyPurchasePolicyShape")
        void GivenEventPurchasePolicy_WhenReadAndSavedBack_ThenThePolicyIsUnchanged(PurchasePolicy stored) {
                ownerCanEditTheEventPolicy(stored);

                PurchasePolicyDTO read = eventService.getEventPurchasePolicy(OWNER_TOKEN, COMPANY_ID, EVENT_ID);
                eventService.setEventPolicies(OWNER_TOKEN, new EventPolicyConfigDTO(COMPANY_ID, EVENT_ID, read));

                assertEquals(stored, event.getPurchasePolicy(), "read as " + read);
        }

        @Test
        void GivenEventPurchasePolicyOfAnUnmappedType_WhenReadEventPolicy_ThenRefusedInsteadOfReadAsNone() {
                ownerCanEditTheEventPolicy(new UnmappedPurchasePolicy());

                assertThrows(UnsupportedOperationException.class,
                                () -> eventService.getEventPurchasePolicy(OWNER_TOKEN, COMPANY_ID, EVENT_ID),
                                "a policy the mapping does not know must be refused, not read as NONE");
        }
}
