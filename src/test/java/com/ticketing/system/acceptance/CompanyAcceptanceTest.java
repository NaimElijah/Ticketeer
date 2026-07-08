package com.ticketing.system.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.shared.dto.AppointmentResponseDTO;
import com.ticketing.system.shared.dto.AppointmentRevokeDTO;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CompanyRegistrationDTO;
import com.ticketing.system.catalog.application.dto.EventCreationDTO;
import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.organization.application.dto.ManagerAppointmentRequestDTO;
import com.ticketing.system.organization.application.dto.PermissionEditDTO;
import com.ticketing.system.organization.application.dto.ProductionCompanyDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.shared.dto.EventUpdateDTO;
import com.ticketing.system.shared.dto.LocationDTO;
import com.ticketing.system.shared.dto.ShowDateDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.organization.application.service.CompanyAnalyticsService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.domain.InventoryZone;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.identity.domain.User;

@SpringBootTest
@ActiveProfiles("test")
class CompanyAcceptanceTest {
        @Autowired
        private AuthenticationService authService;
        @Autowired
        private CompanyManagementService companyService;
        @Autowired
        private EventManagementService eventManagementService;
        @Autowired
        private EventRepository eventRepository;
        @Autowired
        private UserRepository userRepository;

        @Autowired
        private CompanyAnalyticsService companyAnalyticsService;

        @Autowired
        private OrderReceiptRepository orderReceiptRepository;

        private AuthTokenDTO registerAndLoginMember(String name) {
                String sid = authService.startGuestSession().sessionId();

                authService.register(new RegisterRequestDTO(
                                name,
                                name + "@test.com",
                                "Password1",
                                sid,
                                20));

                return authService
                                .login(new LoginRequestDTO(name, "Password1", sid))
                                .authToken();
        }

        // UC-18
        @Test
        @Disabled("UC-18 main: Member registers company → becomes Founder/Owner")
        void GivenMember_WhenRegisterCompany_ThenFounderOwner() {
        }

        // UC-19
        @Test
        @Disabled("UC-19 main: Owner adds event to catalog")
        void GivenOwner_WhenAddEvent_ThenInDraft() {
        }

        @Test
        @Disabled("UC-19 negative: non-permitted Manager rejected")
        void GivenManagerNoPermission_WhenAddEvent_ThenRejected() {
        }

        // UC-20
        @Test
        void GivenOwner_WhenConfigureVenueMapWithCapacity_ThenInventoryZoneCapacityAdded() {
                AuthTokenDTO owner = registerAndLoginMember("capacityOwner");

                int companyId = companyService.registerCompany(
                                owner.token(),
                                new CompanyRegistrationDTO("capacityCompany", "desc")).companyId();

                EventCreationDTO eventRequest = new EventCreationDTO(
                                companyId,
                                "Capacity Test Event",
                                "Testing inventory zone capacity",
                                List.of("Test Artist", "Another Artist"),
                                EventCategory.CONCERT,
                                4.5,
                                new Location("Test Venue", "Test City"),
                                List.of(new ShowDate(
                                                LocalDateTime.now().plusDays(1),
                                                LocalDateTime.now().plusDays(30))),
                                null);
                EventDetailDTO event = eventManagementService.addEvent(owner.token(), eventRequest);

                eventManagementService.configureVenueMap(owner.token(), companyId, new VenueMapConfigDTO(
                                event.eventId(),
                                "Test Venue",
                                3, 3,
                                List.of(new VenueMapConfigDTO.ZoneConfigDTO("Standing Zone A", false, 100, null,
                                                50.0, null))));

                Event storedEvent = eventRepository.findById(Integer.parseInt(event.eventId()));

                // eventManagementService.updateStandingZoneCapacity(owner.token(), companyId,
                // storedEvent.getId(), 1, 150);
                //
                eventManagementService.addPlacesToStandingZone(owner.token(), companyId, storedEvent.getId(), 1, 50);
                InventoryZone zone = storedEvent.getVenueMap().getInventoryZones().stream()
                                .filter(z -> z.getId() == 1)
                                .findFirst()
                                .orElseThrow(() -> new RuntimeException("Zone not found"));

                assertEquals(150, zone.getCapacity());

        }

        // UC-21
        @Test
        @Disabled("UC-21 main: Owner sets event-level purchase policy")
        void GivenOwner_WhenSetEventPolicy_ThenStored() {
        }

        @Test
        @Disabled("UC-21 main: Owner sets company-level discount policy")
        void GivenOwner_WhenSetCompanyPolicy_ThenStored() {
        }

        @Test
        void GivenCompanyWithNoEvents_WhenViewSalesHistory_ThenEmpty() {
        AuthTokenDTO owner = registerAndLoginMember("salesOwner1");
        int companyId = companyService.registerCompany(
                owner.token(), new CompanyRegistrationDTO("SalesCo1", "desc")).companyId();

        PurchaseHistoryDTO result = companyAnalyticsService.salesHistory(companyId);

        assertTrue(result.records().isEmpty());
        }

        @Test
        void GivenEventWithNoOrders_WhenViewSalesHistory_ThenEmpty() {
        AuthTokenDTO owner = registerAndLoginMember("salesOwner2");
        int companyId = companyService.registerCompany(
                owner.token(), new CompanyRegistrationDTO("SalesCo2", "desc")).companyId();

        eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                companyId, "Empty Event", "desc", List.of("Artist"),
                EventCategory.CONCERT, 4.0,
                new Location("Israel", "Tel Aviv"),
                List.of(new ShowDate(LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(3))),
                null));

        PurchaseHistoryDTO result = companyAnalyticsService.salesHistory(companyId);

        assertTrue(result.records().isEmpty());
        }

                @Test
        void GivenOneCompletedOrder_WhenViewSalesHistory_ThenOneRecordWithCorrectTotal() {
        AuthTokenDTO owner = registerAndLoginMember("salesOwner3");
        int companyId = companyService.registerCompany(
                owner.token(), new CompanyRegistrationDTO("SalesCo3", "desc")).companyId();

        EventDetailDTO event = eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                companyId, "Sales Event", "desc", List.of("Artist"),
                EventCategory.CONCERT, 4.5,
                new Location("Israel", "Tel Aviv"),
                List.of(new ShowDate(LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(3))),
                null));

        int eventId = Integer.parseInt(event.eventId());
        int receiptId = orderReceiptRepository.nextId();
        OrderReceipt receipt = OrderReceipt.forMember(
                receiptId, owner.userId(), 80.0,
                List.of(new ReceiptLine(1, 80.0, eventId, 1, null, LocalDateTime.now())));
        orderReceiptRepository.save(receipt);

        PurchaseHistoryDTO result = companyAnalyticsService.salesHistory(companyId);

        assertEquals(1, result.records().size());
        assertEquals(80.0, result.records().get(0).totalPaid());
        }

        @Test
        void GivenTwoCompletedOrders_WhenViewSalesHistory_ThenTwoRecords() {
        AuthTokenDTO owner = registerAndLoginMember("salesOwner4");
        int companyId = companyService.registerCompany(
                owner.token(), new CompanyRegistrationDTO("SalesCo4", "desc")).companyId();

        EventDetailDTO event = eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                companyId, "Multi Sales Event", "desc", List.of("Artist"),
                EventCategory.CONCERT, 4.5,
                new Location("Israel", "Haifa"),
                List.of(new ShowDate(LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(3))),
                null));

        int eventId = Integer.parseInt(event.eventId());
        orderReceiptRepository.save(OrderReceipt.forMember(
                orderReceiptRepository.nextId(), owner.userId(), 50.0,
                List.of(new ReceiptLine(1, 50.0, eventId, 1, null, LocalDateTime.now()))));
        orderReceiptRepository.save(OrderReceipt.forMember(
                orderReceiptRepository.nextId(), owner.userId(), 30.0,
                List.of(new ReceiptLine(2, 30.0, eventId, 1, null, LocalDateTime.now()))));

        PurchaseHistoryDTO result = companyAnalyticsService.salesHistory(companyId);

        assertEquals(2, result.records().size());
        }

        @Test
        void GivenRefundedOrder_WhenViewSalesHistory_ThenStillAppearsWithRefundedFlag() {
        AuthTokenDTO owner = registerAndLoginMember("salesOwner5");
        int companyId = companyService.registerCompany(
                owner.token(), new CompanyRegistrationDTO("SalesCo5", "desc")).companyId();

        EventDetailDTO event = eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                companyId, "Refund Event", "desc", List.of("Artist"),
                EventCategory.CONCERT, 4.5,
                new Location("Israel", "Jerusalem"),
                List.of(new ShowDate(LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(3))),
                null));

        int eventId = Integer.parseInt(event.eventId());
        int receiptId = orderReceiptRepository.nextId();
        OrderReceipt receipt = OrderReceipt.forMember(
                receiptId, owner.userId(), 60.0,
                List.of(new ReceiptLine(1, 60.0, eventId, 1, null, LocalDateTime.now())));
        receipt.markRefunded();
        orderReceiptRepository.save(receipt);

        PurchaseHistoryDTO result = companyAnalyticsService.salesHistory(companyId);

        assertEquals(1, result.records().size());
        assertTrue(result.records().get(0).refunded());
        }


        // UC-23
        @Test
        @Disabled("UC-23 main: appoint co-Owner → PENDING")
        void GivenOwner_WhenAppointOwner_ThenPending() {
        }

        @Test
        @Disabled("UC-23 alt: target accepts → ACTIVE")
        void GivenPending_WhenAccept_ThenActive() {
        }

        @Test
        @Disabled("UC-23 negative: cycle prevented (II.4.8.3)")
        void GivenCyclicalAppointment_WhenAttempt_ThenRejected() {
        }

        // UC-24
        @Test
        void GivenOwner_WhenAppointManager_ThenWithPermissions() {
                AuthTokenDTO owner = registerAndLoginMember("owner24a");
                AuthTokenDTO manager = registerAndLoginMember("manager24a");

                ProductionCompanyDTO companyDto = companyService.registerCompany(
                                owner.token(),
                                new CompanyRegistrationDTO("company24a", "desc"));

                int companyId = companyDto.companyId();

                List<Permission> permissions = List.of(
                                Permission.MANAGE_INVENTORY,
                                Permission.CONFIGURE_VENUE);

                companyService.appointManager(owner.token(),
                                new ManagerAppointmentRequestDTO(companyId, manager.userId(), permissions));

                User managerUser = userRepository.getUserById(manager.userId());

                assertNotEquals(null, managerUser.getPendingCompanyAppointment(companyId));
                assertEquals(permissions, managerUser.getPendingCompanyAppointment(companyId).getPermissions().stream()
                                .toList());
        }

        @Test
        void GivenAppointer_WhenEditPermissions_ThenUpdated() {
                AuthTokenDTO owner = registerAndLoginMember("owner24b");
                AuthTokenDTO manager = registerAndLoginMember("manager24b");

                int companyId = companyService.registerCompany(
                                owner.token(),
                                new CompanyRegistrationDTO("company24b", "desc")).companyId();

                companyService.appointManager(owner.token(), new ManagerAppointmentRequestDTO(companyId,
                                manager.userId(), List.of(Permission.MANAGE_INVENTORY, Permission.CONFIGURE_VENUE)));

                companyService.respondToAppointment(manager.token(), new AppointmentResponseDTO(companyId, true));

                List<Permission> updated = List.of(
                                Permission.CONFIGURE_VENUE,
                                Permission.EDIT_POLICIES);

                companyService.editManagerPermissions(owner.token(),
                                new PermissionEditDTO(companyId, manager.userId(), updated));

                User managerUser = userRepository.getUserById(manager.userId());

                assertEquals(updated,
                                managerUser.getActiveCompanyAppointment(companyId).getPermissions().stream().toList());
        }

        @Test
        void GivenDifferentOwner_WhenEditPermissions_ThenRejected() {
                AuthTokenDTO owner = registerAndLoginMember("owner24c");
                AuthTokenDTO other = registerAndLoginMember("other24c");
                AuthTokenDTO manager = registerAndLoginMember("manager24c");

                int companyId = companyService.registerCompany(
                                owner.token(),
                                new CompanyRegistrationDTO("company24c", "desc")).companyId();

                List<Permission> original = List.of(Permission.MANAGE_INVENTORY);

                companyService.appointManager(owner.token(),
                                new ManagerAppointmentRequestDTO(companyId, manager.userId(), original));

                companyService.respondToAppointment(manager.token(), new AppointmentResponseDTO(companyId, true));

                assertThrows(RuntimeException.class, () -> companyService.editManagerPermissions(
                                other.token(),
                                new PermissionEditDTO(companyId, manager.userId(), List.of(Permission.EDIT_POLICIES))));

                User managerUser = userRepository.getUserById(manager.userId());
                assertEquals(original,
                                managerUser.getActiveCompanyAppointment(companyId).getPermissions().stream().toList());
        }

        @Test
        void GivenAppointer_WhenRevokeManager_ThenRevoked() {
                AuthTokenDTO owner = registerAndLoginMember("owner24d");
                AuthTokenDTO manager = registerAndLoginMember("manager24d");

                int companyId = companyService.registerCompany(
                                owner.token(),
                                new CompanyRegistrationDTO("company24d", "desc")).companyId();

                companyService.appointManager(
                                owner.token(),
                                new ManagerAppointmentRequestDTO(companyId, manager.userId(),
                                                List.of(Permission.MANAGE_INVENTORY)));

                companyService.respondToAppointment(manager.token(), new AppointmentResponseDTO(companyId, true));

                companyService.RevokeAppointment(
                                owner.token(),
                                new AppointmentRevokeDTO(companyId, manager.userId()));

                User managerUser = userRepository.getUserById(manager.userId());

                assertNull(managerUser.getActiveCompanyAppointment(companyId));
        }

        // UC-25
        @Test
        @Disabled("UC-25 main: Owner views organizational tree (ACTIVE only)")
        void GivenOwner_WhenViewOrgTree_ThenNestedActiveOnly() {
        }

        // -------------------------------------------------------------------------
        // getEventDetail — acceptance tests
        // -------------------------------------------------------------------------

        @Test
        void GivenOwnerAddsEvent_WhenGetEventDetail_ThenReturnsExpectedFields() {
                AuthTokenDTO owner = registerAndLoginMember("detailOwner1");
                int companyId = companyService.registerCompany(
                                owner.token(), new CompanyRegistrationDTO("DetailCo1", "desc")).companyId();

                EventDetailDTO created = eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                                companyId, "Summer Festival", "desc", List.of("DJ Max"),
                                EventCategory.FESTIVAL, 4.5,
                                new Location("Germany", "Berlin"),
                                List.of(new ShowDate(LocalDateTime.now().plusDays(1),
                                                LocalDateTime.now().plusDays(1).plusHours(4))),
                                null));

                EventDetailDTO result = eventManagementService.getEventDetail(owner.token(),
                                Integer.parseInt(created.eventId()));

                assertEquals(created.eventId(), result.eventId());
                assertEquals("Summer Festival", result.name());
                assertEquals(EventCategory.FESTIVAL, result.category());
                assertEquals(EventStatus.DRAFT, result.status());
                assertEquals("DetailCo1", result.companyName());
                assertEquals("desc", result.description());
        }

        @Test
        void GivenManagerWithConfigureVenuePermission_WhenGetEventDetail_ThenReturnsDTO() {
                AuthTokenDTO owner = registerAndLoginMember("detailOwner2");
                AuthTokenDTO manager = registerAndLoginMember("detailManager2");
                int companyId = companyService.registerCompany(
                                owner.token(), new CompanyRegistrationDTO("DetailCo2", "desc")).companyId();

                companyService.appointManager(owner.token(),
                                new ManagerAppointmentRequestDTO(companyId, manager.userId(),
                                                List.of(Permission.CONFIGURE_VENUE)));
                companyService.respondToAppointment(manager.token(), new AppointmentResponseDTO(companyId, true));

                EventDetailDTO created = eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                                companyId, "Manager View Event", "desc", List.of("Artist"),
                                EventCategory.MUSIC, 4.0,
                                new Location("France", "Paris"),
                                List.of(new ShowDate(LocalDateTime.now().plusDays(1),
                                                LocalDateTime.now().plusDays(1).plusHours(3))),
                                null));

                EventDetailDTO result = eventManagementService.getEventDetail(manager.token(),
                                Integer.parseInt(created.eventId()));

                assertEquals(created.eventId(), result.eventId());
                assertEquals("Manager View Event", result.name());
        }

        @Test
        void GivenUserNotInCompany_WhenGetEventDetail_ThenThrows() {
                AuthTokenDTO owner = registerAndLoginMember("detailOwner3");
                AuthTokenDTO outsider = registerAndLoginMember("outsider3");
                int companyId = companyService.registerCompany(
                                owner.token(), new CompanyRegistrationDTO("DetailCo3", "desc")).companyId();

                EventDetailDTO created = eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                                companyId, "Private Event", "desc", List.of("Artist"),
                                EventCategory.MUSIC, 4.0,
                                new Location("Spain", "Madrid"),
                                List.of(new ShowDate(LocalDateTime.now().plusDays(1),
                                                LocalDateTime.now().plusDays(1).plusHours(3))),
                                null));

                assertThrows(RuntimeException.class,
                                () -> eventManagementService.getEventDetail(outsider.token(),
                                                Integer.parseInt(created.eventId())));
        }

        // -------------------------------------------------------------------------
        // editEventDetails — acceptance tests
        // -------------------------------------------------------------------------

        @Test
        void GivenOwner_WhenEditEventName_ThenNameIsUpdatedInRepository() {
                AuthTokenDTO owner = registerAndLoginMember("editOwner1");
                int companyId = companyService.registerCompany(
                                owner.token(), new CompanyRegistrationDTO("EditCo1", "desc")).companyId();

                EventDetailDTO created = eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                                companyId, "Original Name", "desc", List.of("Artist"),
                                EventCategory.MUSIC, 4.0,
                                new Location("Italy", "Rome"),
                                List.of(new ShowDate(LocalDateTime.now().plusDays(1),
                                                LocalDateTime.now().plusDays(1).plusHours(3))),
                                null));

                eventManagementService.editEventDetails(owner.token(),
                                new EventUpdateDTO(created.eventId(), "Updated Name", null, null, null, null));

                Event stored = eventRepository.findById(Integer.parseInt(created.eventId()));
                assertEquals("Updated Name", stored.getName());
        }

        @Test
        void GivenOwner_WhenEditEventCategory_ThenCategoryIsUpdatedInRepository() {
                AuthTokenDTO owner = registerAndLoginMember("editOwner2");
                int companyId = companyService.registerCompany(
                                owner.token(), new CompanyRegistrationDTO("EditCo2", "desc")).companyId();

                EventDetailDTO created = eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                                companyId, "Category Event", "desc", List.of("Artist"),
                                EventCategory.MUSIC, 4.0,
                                new Location("Japan", "Tokyo"),
                                List.of(new ShowDate(LocalDateTime.now().plusDays(1),
                                                LocalDateTime.now().plusDays(1).plusHours(3))),
                                null));

                eventManagementService.editEventDetails(owner.token(),
                                new EventUpdateDTO(created.eventId(), null, null, "SPORTS", null, null));

                Event stored = eventRepository.findById(Integer.parseInt(created.eventId()));
                assertEquals(EventCategory.SPORTS, stored.getCategory());
        }

        @Test
        void GivenOwner_WhenEditNameAndCategoryTogether_ThenBothUpdated() {
                AuthTokenDTO owner = registerAndLoginMember("editOwner3");
                int companyId = companyService.registerCompany(
                                owner.token(), new CompanyRegistrationDTO("EditCo3", "desc")).companyId();

                EventDetailDTO created = eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                                companyId, "Old Name", "desc", List.of("Artist"),
                                EventCategory.MUSIC, 4.0,
                                new Location("Brazil", "Rio"),
                                List.of(new ShowDate(LocalDateTime.now().plusDays(1),
                                                LocalDateTime.now().plusDays(1).plusHours(3))),
                                null));

                eventManagementService.editEventDetails(owner.token(),
                                new EventUpdateDTO(created.eventId(), "New Name", null, "THEATER", null, null));

                Event stored = eventRepository.findById(Integer.parseInt(created.eventId()));
                assertEquals("New Name", stored.getName());
                assertEquals(EventCategory.THEATER, stored.getCategory());
        }

        @Test
        void GivenManagerWithoutConfigureVenuePermission_WhenEditEventDetails_ThenThrows() {
                AuthTokenDTO owner = registerAndLoginMember("editOwner4");
                AuthTokenDTO manager = registerAndLoginMember("editManager4");
                int companyId = companyService.registerCompany(
                                owner.token(), new CompanyRegistrationDTO("EditCo4", "desc")).companyId();

                companyService.appointManager(owner.token(),
                                new ManagerAppointmentRequestDTO(companyId, manager.userId(),
                                                List.of(Permission.VIEW_SALES)));
                companyService.respondToAppointment(manager.token(), new AppointmentResponseDTO(companyId, true));

                EventDetailDTO created = eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                                companyId, "Restricted Event", "desc", List.of("Artist"),
                                EventCategory.MUSIC, 4.0,
                                new Location("UK", "London"),
                                List.of(new ShowDate(LocalDateTime.now().plusDays(1),
                                                LocalDateTime.now().plusDays(1).plusHours(3))),
                                null));

                assertThrows(RuntimeException.class,
                                () -> eventManagementService.editEventDetails(manager.token(),
                                                new EventUpdateDTO(created.eventId(), "Blocked Edit", null, null, null,
                                                                null)));
        }

        @Test
        void GivenOwner_WhenEditAllFields_ThenDescriptionLocationAndShowDatesPersist() {
                AuthTokenDTO owner = registerAndLoginMember("editOwner5");
                int companyId = companyService.registerCompany(
                                owner.token(), new CompanyRegistrationDTO("EditCo5", "desc")).companyId();

                EventDetailDTO created = eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                                companyId, "Full Edit Event", "original desc", List.of("Artist"),
                                EventCategory.MUSIC, 4.0,
                                new Location("Italy", "Rome"),
                                List.of(new ShowDate(LocalDateTime.now().plusDays(1),
                                                LocalDateTime.now().plusDays(1).plusHours(3))),
                                null));

                LocalDateTime newStart = LocalDateTime.now().plusDays(20);
                eventManagementService.editEventDetails(owner.token(), new EventUpdateDTO(
                                created.eventId(), "Full Edit Renamed", "updated desc", "THEATER",
                                new LocationDTO("France", "Paris"),
                                List.of(new ShowDateDTO(newStart, newStart.plusHours(2)))));

                EventDetailDTO result = eventManagementService.getEventDetail(owner.token(),
                                Integer.parseInt(created.eventId()));
                assertEquals("Full Edit Renamed", result.name());
                assertEquals("updated desc", result.description());
                assertEquals(EventCategory.THEATER, result.category());
                assertEquals(new Location("France", "Paris"), result.location());

                Event stored = eventRepository.findById(Integer.parseInt(created.eventId()));
                assertEquals(1, stored.getShowDates().size());
                assertEquals(newStart, stored.getShowDates().get(0).getStartTime());
        }
}
