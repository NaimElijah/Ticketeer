package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.ticketing.system.Core.Application.interfaces.IPaymentGateway;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.Core.Application.interfaces.INotificationService;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.Core.Application.services.EventManagementService;
import com.ticketing.system.Core.Domain.Tickets.ITicketRepository;
import com.ticketing.system.Core.Domain.Tickets.Ticket;
import com.ticketing.system.Core.Domain.Tickets.TicketStatus;
import com.ticketing.system.Core.Domain.company.CompanyStatus;
import com.ticketing.system.Core.Domain.company.IProductionCompanyRepository;
import com.ticketing.system.Core.Domain.company.ProductionCompany;
import com.ticketing.system.Core.Domain.events.Event;
import com.ticketing.system.Core.Domain.events.EventStatus;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.Core.Domain.events.IEventRepository;
import com.ticketing.system.Core.Domain.events.InventoryZone;
import com.ticketing.system.Core.Domain.events.StandingZone;
import com.ticketing.system.Core.Domain.events.Location;
import com.ticketing.system.Core.Domain.events.Seat;
import com.ticketing.system.Core.Domain.events.SeatedZone;
import com.ticketing.system.Core.Domain.events.ShowDate;
import com.ticketing.system.Core.Domain.events.VenueMap;
import com.ticketing.system.Core.Domain.orders.IOrderReceiptRepository;
import com.ticketing.system.Core.Domain.orders.OrderReceipt;
import com.ticketing.system.Core.Domain.orders.ReceiptLine;
import com.ticketing.system.Core.Domain.orders.TransactionRecord;
import com.ticketing.system.Core.Application.dto.RefundResultDTO;
import com.ticketing.system.Core.Application.dto.ZoneDetailDTO;
import com.ticketing.system.Core.Application.dto.VenueLayoutDTO;
import com.ticketing.system.Core.Application.dto.GridPlacementDTO;
import com.ticketing.system.Core.Application.dto.VenueMapConfigDTO;
import com.ticketing.system.Core.Application.dto.EventDetailDTO;
import com.ticketing.system.Core.Application.dto.EventUpdateDTO;
import com.ticketing.system.Core.Application.dto.LocationDTO;
import com.ticketing.system.Core.Application.dto.ShowDateDTO;
import com.ticketing.system.Core.Domain.events.DiscountPolicy;
import com.ticketing.system.Core.Domain.events.EventCategory;
import com.ticketing.system.Core.Domain.users.Permission;
import com.ticketing.system.identity.domain.User;

class EventManagementServiceTest {

        private IEventRepository mockEventRepo;
        private IProductionCompanyRepository mockCompanyRepo;
        private ITicketRepository mockTicketRepo;
        private SessionManager sessionManager;
        private EventManagementService eventService;
        private IOrderReceiptRepository orderReceiptRepository;
        private IPaymentGateway paymentGateway;
        private UserRepository userRepository;
        private INotificationService notificationService;

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

        private ProductionCompany company;
        private InventoryZone zone;
        private VenueMap venueMap;
        private Event event;
        private User ownerUser;
        private User managerUser;
        private User inventoryManagerUser;

        @BeforeEach
        public void setUp() {
                mockEventRepo = mock(IEventRepository.class);
                mockCompanyRepo = mock(IProductionCompanyRepository.class);
                mockTicketRepo = mock(ITicketRepository.class);
                sessionManager = mock(SessionManager.class);
                orderReceiptRepository = mock(IOrderReceiptRepository.class);
                paymentGateway = mock(IPaymentGateway.class);
                userRepository = mock(UserRepository.class);
                notificationService = mock(INotificationService.class);

                eventService = new EventManagementService(
                                mockEventRepo,
                                mockCompanyRepo,
                                mockTicketRepo,
                                sessionManager,
                                orderReceiptRepository,
                                paymentGateway,
                                userRepository,
                                notificationService);

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
                                null,
                                new DiscountPolicy(0));
                ownerUser = new User(OWNER_ID, "Owner Name", "owner@example.com", "hashedpassword", 50);
                ownerUser.addFounderAppointment(COMPANY_ID);
                managerUser = new User(MANAGER_ID, "Manager Name", "manager@example.com", "hashedpassword", 40);
                managerUser.receiveManagerAppointment(COMPANY_ID, OWNER_ID, List.of(Permission.CONFIGURE_VENUE));
                managerUser.acceptInvitation(COMPANY_ID);
                inventoryManagerUser = new User(INVENTORY_MANAGER_ID, "Inventory Manager",
                                "inventory@example.com", "hashedpassword", 40);
                inventoryManagerUser.receiveManagerAppointment(COMPANY_ID, OWNER_ID,
                                List.of(Permission.MANAGE_INVENTORY));
                inventoryManagerUser.acceptInvitation(COMPANY_ID);
        }

        private OrderReceipt setupStateBasedHappyPath() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(userRepository.getUserById(MANAGER_ID)).thenReturn(managerUser);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                ReceiptLine line = new ReceiptLine(1, 100.0, EVENT_ID, 1, "A1", java.time.LocalDateTime.now());
                OrderReceipt realReceipt = OrderReceipt.forMember(99, OWNER_ID, 100.0, List.of(line));
                realReceipt.addTransaction(
                                TransactionRecord.paymentCharge(42, "test-gateway", 100.0, "ILS",
                                                java.time.LocalDateTime.now()));

                when(paymentGateway.getId()).thenReturn("test-gateway");
                when(paymentGateway.refund(anyInt(), anyDouble())).thenReturn(
                                new RefundResultDTO("refund-tx-1", "99", 100.0, java.time.LocalDateTime.now(),
                                                List.of(), List.of()));
                when(mockTicketRepo.findByEventId(EVENT_ID)).thenReturn(List.of());

                when(orderReceiptRepository.findByEventId(EVENT_ID))
                                .thenReturn(List.of(realReceipt));

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

        @Test
        public void GivenEventAlreadyCanceled_WhenCancelEventAndRefund_ThenReceiptStateRemainsUnchanged() {
                OrderReceipt receipt = setupStateBasedHappyPath();
                event.transitionToCanceled("cancelled");
                ; // Manually cancel it beforehand

                eventService.cancelEventAndRefund(OWNER_TOKEN, EVENT_ID);

                assertEquals(false, receipt.wasRefunded());
        }

        @Test
        public void GivenValidRequest_WhenCancelEventAndRefund_ThenEventStateIsCanceled() {
                setupStateBasedHappyPath();

                eventService.cancelEventAndRefund(OWNER_TOKEN, EVENT_ID);

                assertTrue(event.getStatus() == EventStatus.CANCELED);
        }

        @Test
        public void GivenValidRequest_WhenCancelEventAndRefund_ThenReceiptStateIsMarkedRefunded() {
                OrderReceipt realReceipt = setupStateBasedHappyPath();

                eventService.cancelEventAndRefund(OWNER_TOKEN, EVENT_ID);

                assertTrue(realReceipt.wasRefunded());
        }

        @Test
        public void GivenMemberReceipts_WhenCancelEventAndRefund_ThenTicketHoldersAreNotified() {
                setupStateBasedHappyPath();

                eventService.cancelEventAndRefund(OWNER_TOKEN, EVENT_ID);

                verify(notificationService).notifyEventCancelled(eq(OWNER_ID), eq(EVENT_ID), eq("Concert"));
        }

        @Test
        public void GivenPaidTicket_WhenCancelEventAndRefund_ThenTicketStatusIsMarkedRefunded() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(userRepository.getUserById(MANAGER_ID)).thenReturn(managerUser);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(orderReceiptRepository.findByEventId(EVENT_ID)).thenReturn(List.of());

                Ticket paidTicket = new Ticket(EVENT_ID, ZONE_ID, ORDER_RECEIPT_ID, null, 100.0, 1, "BARCODE123");
                // paidTicket.markPaid();
                when(mockTicketRepo.findByEventId(EVENT_ID)).thenReturn(List.of(paidTicket));

                eventService.cancelEventAndRefund(OWNER_TOKEN, EVENT_ID);

                assertEquals(TicketStatus.REFUNDED, paidTicket.getStatus());
        }

        @Test
        public void GivenIssuedTicket_WhenCancelEventAndRefund_ThenTicketStatusIsMarkedRefunded() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(userRepository.getUserById(MANAGER_ID)).thenReturn(managerUser);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(orderReceiptRepository.findByEventId(EVENT_ID)).thenReturn(List.of());

                Ticket issuedTicket = new Ticket(EVENT_ID, ZONE_ID, ORDER_RECEIPT_ID, null, 100.0, 1, "BARCODE123");

                issuedTicket.markIssued("BARCODE123");

                when(mockTicketRepo.findByEventId(EVENT_ID)).thenReturn(List.of(issuedTicket));

                eventService.cancelEventAndRefund(OWNER_TOKEN, EVENT_ID);

                assertEquals(TicketStatus.REFUNDED, issuedTicket.getStatus());
        }

        @Test
        public void GivenAvailableTicket_WhenCancelEventAndRefund_ThenTicketStatusRemainsUnchanged() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(userRepository.getUserById(MANAGER_ID)).thenReturn(managerUser);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(orderReceiptRepository.findByEventId(EVENT_ID)).thenReturn(List.of());

                Ticket availableTicket = new Ticket(EVENT_ID, ZONE_ID, ORDER_RECEIPT_ID, null, 100.0, 1, "BARCODE123");

                availableTicket.release();

                when(mockTicketRepo.findByEventId(EVENT_ID)).thenReturn(List.of(availableTicket));

                eventService.cancelEventAndRefund(OWNER_TOKEN, EVENT_ID);

                assertEquals(TicketStatus.VOIDED, availableTicket.getStatus());
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
                                                null));

                assertEquals("New Concert Name", event.getName());
        }

        @Test
        void GivenOwnerAndNewCategory_WhenEditEventDetails_ThenCategoryIsUpdated() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);

                eventService.editEventDetails(OWNER_TOKEN,
                                new EventUpdateDTO(String.valueOf(EVENT_ID), null, null, "MUSIC", null, null));

                assertEquals(EventCategory.MUSIC, event.getCategory());
        }

        @Test
        void GivenBothFieldsNull_WhenEditEventDetails_ThenNothingChanges() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);

                eventService.editEventDetails(OWNER_TOKEN,
                                new EventUpdateDTO(String.valueOf(EVENT_ID), null, null, null, null, null));

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
                                new EventUpdateDTO(String.valueOf(EVENT_ID), "Manager Edited", null, null, null, null));

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
                                new EventUpdateDTO(String.valueOf(EVENT_ID), "Nope", null, null, null, null)));
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
                when(orderReceiptRepository.findByEventId(EVENT_ID)).thenReturn(List.of());
                when(mockTicketRepo.findByEventId(EVENT_ID)).thenReturn(List.of());

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
                                new EventUpdateDTO(String.valueOf(EVENT_ID), "New Name", null, null, null, null)));
        }

        @Test
        void GivenEventNotFound_WhenEditEventDetails_ThenThrows() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenThrow(new RuntimeException("Event not found"));

                assertThrows(RuntimeException.class, () -> eventService.editEventDetails(OWNER_TOKEN,
                                new EventUpdateDTO(String.valueOf(EVENT_ID), "New Name", null, null, null, null)));
        }

        @Test
        void GivenNonNumericEventId_WhenEditEventDetails_ThenThrowsIllegalArgumentException() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);

                assertThrows(IllegalArgumentException.class, () -> eventService.editEventDetails(OWNER_TOKEN,
                                new EventUpdateDTO("not-a-number", "New Name", null, null, null, null)));
        }

        @Test
        void GivenUserNotInCompany_WhenEditEventDetails_ThenThrows() {
                User stranger = new User(99, "Stranger", "stranger@test.com", "hashedpw", 25);
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(99);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(userRepository.getUserById(99)).thenReturn(stranger);

                assertThrows(RuntimeException.class, () -> eventService.editEventDetails(OWNER_TOKEN,
                                new EventUpdateDTO(String.valueOf(EVENT_ID), "New Name", null, null, null, null)));
        }

        @Test
        void GivenEventOnSale_WhenEditEventDetails_ThenThrows() {
                Event onSaleEvent = new Event(
                                EVENT_ID, "Concert", 4.5, List.of("Artist1"),
                                EventCategory.CONCERT, COMPANY_ID, EventStatus.ON_SALE, venueMap,
                                List.of(new ShowDate(LocalDateTime.now().plusDays(10),
                                                LocalDateTime.now().plusDays(10).plusHours(2))),
                                null, new DiscountPolicy(0));
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(onSaleEvent);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);

                assertThrows(RuntimeException.class, () -> eventService.editEventDetails(OWNER_TOKEN,
                                new EventUpdateDTO(String.valueOf(EVENT_ID), "New Name", null, null, null, null)));
        }

        @Test
        void GivenUnknownCategory_WhenEditEventDetails_ThenThrowsIllegalArgumentException() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(event);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);

                assertThrows(IllegalArgumentException.class, () -> eventService.editEventDetails(OWNER_TOKEN,
                                new EventUpdateDTO(String.valueOf(EVENT_ID), null, null, "INVALID_CATEGORY", null,
                                                null)));
        }

        @Test
        void GivenEventInDraftState_WhenEditEventDetails_ThenSucceeds() {
                Event draftEvent = new Event(
                                EVENT_ID, "Draft Concert", 4.5, List.of("Artist1"),
                                EventCategory.CONCERT, COMPANY_ID, EventStatus.DRAFT, venueMap,
                                List.of(new ShowDate(LocalDateTime.now().plusDays(10),
                                                LocalDateTime.now().plusDays(10).plusHours(2))),
                                null, new DiscountPolicy(0));
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(draftEvent);
                when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);

                eventService.editEventDetails(OWNER_TOKEN,
                                new EventUpdateDTO(String.valueOf(EVENT_ID), "Updated Draft Name", null, null, null,
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
                                                null, null));

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
                                                new LocationDTO("France", "Paris"), null));

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
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(null);
                when(mockEventRepo.findByCompanyId(COMPANY_ID)).thenReturn(List.of(event));

                assertThrows(RuntimeException.class,
                                () -> eventService.listEventsForCompany(OWNER_TOKEN, COMPANY_ID));
        }

        //////////////////////////////////////// Tests for getEventZones
        @Test
        void GivenEventNotFound_WhenGetEventZones_ThenThrowsException() {
                when(sessionManager.validateToken(MANAGER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(MANAGER_TOKEN)).thenReturn(MANAGER_ID);
                when(userRepository.getUserById(MANAGER_ID)).thenReturn(managerUser);
                when(mockEventRepo.findById(EVENT_ID)).thenReturn(null);

                assertThrows(RuntimeException.class,
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
                int LIMITED_MANAGER_ID = 3;

                User limitedManager = new User(
                                LIMITED_MANAGER_ID,
                                "Limited Manager",
                                "limited@example.com",
                                "hashedpassword",
                                30);

                limitedManager.receiveManagerAppointment(
                                COMPANY_ID,
                                OWNER_ID,
                                List.of(Permission.MANAGE_INVENTORY));
                limitedManager.acceptInvitation(COMPANY_ID);

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
                                EventStatus.SCHEDULED, gridVenueMap, event.getShowDates(), null, new DiscountPolicy(0));

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

}
