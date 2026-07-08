package com.ticketing.system.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.shared.dto.AppointmentResponseDTO;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CardDetailsDTO;
import com.ticketing.system.shared.dto.CompanyRegistrationDTO;
import com.ticketing.system.catalog.application.dto.EventCreationDTO;
import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.shared.dto.EventPolicyConfigDTO;
import com.ticketing.system.shared.dto.GridPlacementDTO;
import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.organization.application.dto.ManagerAppointmentRequestDTO;
import com.ticketing.system.shared.dto.MarketControlRequestDTO;
import com.ticketing.system.shared.dto.OwnerAppointmentRequestDTO;
import com.ticketing.system.shared.dto.PurchasePolicyDTO;
import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO.ZoneConfigDTO;
import com.ticketing.system.shared.dto.VenueMapDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.organization.domain.Permission;

/**
 * Eval points 8 & 11 — role hierarchy and the layout-vs-policy permission boundary.
 *
 * <p>Builds the founder → owner → manager chain (u1 founds, appoints u2 owner; u2 appoints u3
 * manager with CONFIGURE_VENUE only) and asserts:
 * <ul>
 *   <li>the chain is recorded (founder, owner, manager-with-the-granted-permission);</li>
 *   <li>u3 CAN edit an event's layout (configure venue map) but CANNOT manage purchase policies;</li>
 *   <li>a published event that has a sold ticket is locked against layout changes — sold tickets
 *       can never be orphaned by a reconfiguration.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class RoleChainAndLayoutPermissionAcceptanceTest {

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private ReservationService reservationService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private CatalogService catalogService;
    @Autowired private ProductionCompanyRepository companyRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SystemAdminService systemAdminService;

    /** Reservations/checkout require the trading market open; the test profile boots closed. */
    @BeforeEach
    void ensureMarketOpen() {
        if (systemAdminService.isMarketOpen()) {
            return;
        }
        systemAdminService.initializePlatform(); // idempotent — no-op if already initialized
        AuthTokenDTO admin = authService.signInAsAdmin("admin", "admin");
        systemAdminService.openMarket(new MarketControlRequestDTO("OPEN", "eval acceptance setup", admin.token()));
    }

    @Test
    void GivenFounderAppointsOwnerAppointsManager_ThenChainAndGrantedPermissionsHold() {
        Chain c = buildChain();

        assertEquals(c.u1Id, companyRepository.getCompanyById(c.companyId).getFounderId(),
                "u1 is the founder");
        assertTrue(companyRepository.getCompanyById(c.companyId).isOwner(c.u2Id),
                "u2 (appointed by u1) is an owner");
        assertTrue(userRepository.getUserById(c.u3Id).hasPermissionInCompany(c.companyId, Permission.CONFIGURE_VENUE),
                "u3 (appointed by u2) has the granted CONFIGURE_VENUE permission");
        assertFalse(userRepository.getUserById(c.u3Id).hasPermissionInCompany(c.companyId, Permission.EDIT_POLICIES),
                "u3 was NOT granted EDIT_POLICIES");
    }

    @Test
    void GivenManagerWithVenuePermission_WhenEditingLayout_ThenAllowedButPolicyManagementBlocked() {
        Chain c = buildChain();
        int eventId = createScheduledStandingEvent(c.u1, c.companyId, "Layout Event", 100, 50.0);

        // u3 can edit the event layout (CONFIGURE_VENUE) — reconfiguring with a fresh zone succeeds.
        ZoneConfigDTO newZone = new ZoneConfigDTO("Reworked Standing", false, 120, null, 55.0,
                new GridPlacementDTO(1, 1, 1, 1));
        eventManagementService.configureVenueMap(c.u3.token(), c.companyId,
                new VenueMapConfigDTO(String.valueOf(eventId), "Reworked Arena", 1, 1, List.of(newZone)));
        assertEquals(120, standingZone(c.u3.token(), eventId).getAvailableAmount(),
                "u3's layout edit took effect");

        // u3 cannot manage purchase policies (no EDIT_POLICIES) — the policy path is blocked.
        PurchasePolicyDTO maxPolicy = new PurchasePolicyDTO("MAX_TICKETS", null, null, 4, null);
        assertMissingPermission(
                () -> eventManagementService.setEventPolicies(
                        c.u3.token(), new EventPolicyConfigDTO(c.companyId, eventId, maxPolicy)),
                "EDIT_POLICIES");
    }

    @Test
    void GivenPublishedEventWithSoldTicket_WhenLayoutChangeAttempted_ThenBlocked() {
        Chain c = buildChain();
        int eventId = createScheduledStandingEvent(c.u1, c.companyId, "Sold Event", 100, 50.0);
        eventManagementService.publishEvent(c.u1.token(), c.companyId, eventId);

        // A buyer purchases one ticket -> the event now has sold inventory.
        AuthTokenDTO buyer = registerAndLogin("layoutBuyer");
        int zoneId = standingZone(c.u1.token(), eventId).getId();
        reservationService.reserveForMember(buyer.token(), eventId, zoneId, InventorySelectionDTO.standing(1));
        checkoutService.checkoutMember(buyer.token(), "idem-layout-" + c.u3Id, "ILS", card());

        // Reconfiguring the layout of a live event with a sold ticket is refused — the sale is protected.
        ZoneConfigDTO replacement = new ZoneConfigDTO("Replacement", false, 10, null, 50.0,
                new GridPlacementDTO(1, 1, 1, 1));
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> eventManagementService.configureVenueMap(c.u3.token(), c.companyId,
                        new VenueMapConfigDTO(String.valueOf(eventId), "Hacked Arena", 1, 1, List.of(replacement))));
        String msg = String.valueOf(rootMessage(ex));
        assertTrue(msg.contains("reserved or sold") || msg.contains("DRAFT or SCHEDULED"),
                "a layout change to an event with sold tickets must be blocked; got: " + msg);
    }

    // ---- chain construction --------------------------------------------

    private record Chain(int companyId, AuthTokenDTO u1, int u1Id, AuthTokenDTO u2, int u2Id,
                         AuthTokenDTO u3, int u3Id) { }

    private Chain buildChain() {
        AuthTokenDTO u1 = registerAndLogin("chainFounder");
        int companyId = companyService.registerCompany(
                u1.token(), new CompanyRegistrationDTO("ChainCo-" + u1.userId(), "desc")).companyId();

        AuthTokenDTO u2 = registerAndLogin("chainOwner");
        companyService.appointOwner(u1.token(), new OwnerAppointmentRequestDTO(companyId, u2.userId()));
        companyService.respondToAppointment(u2.token(), new AppointmentResponseDTO(companyId, true));

        AuthTokenDTO u3 = registerAndLogin("chainManager");
        companyService.appointManager(u2.token(),
                new ManagerAppointmentRequestDTO(companyId, u3.userId(), List.of(Permission.CONFIGURE_VENUE)));
        companyService.respondToAppointment(u3.token(), new AppointmentResponseDTO(companyId, true));

        return new Chain(companyId, u1, u1.userId(), u2, u2.userId(), u3, u3.userId());
    }

    // ---- helpers --------------------------------------------------------

    /** addEvent (DRAFT) + configure one standing zone -> event is SCHEDULED and editable. */
    private int createScheduledStandingEvent(AuthTokenDTO owner, int companyId, String name, int capacity, double price) {
        EventDetailDTO ev = eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                companyId, name, "desc", List.of("Artist"), EventCategory.CONCERT, 4.5,
                new Location("Israel", "Tel Aviv"),
                List.of(new ShowDate(LocalDateTime.now().plusDays(10), LocalDateTime.now().plusDays(10).plusHours(3))),
                null));
        int eventId = Integer.parseInt(ev.eventId());
        ZoneConfigDTO zone = new ZoneConfigDTO("Standing", false, capacity, null, price,
                new GridPlacementDTO(1, 1, 1, 1));
        eventManagementService.configureVenueMap(owner.token(), companyId,
                new VenueMapConfigDTO(ev.eventId(), "Arena", 1, 1, List.of(zone)));
        return eventId;
    }

    private com.ticketing.system.shared.dto.InventoryZoneDTO standingZone(String token, int eventId) {
        VenueMapDTO map = catalogService.getEventVenueMap(token, eventId);
        return map.inventoryZones().stream()
                .filter(z -> "STANDING".equals(z.getZoneType()))
                .findFirst().orElseThrow();
    }

    private AuthTokenDTO registerAndLogin(String baseName) {
        String name = baseName + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String sid = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO(name, name + "@test.com", "Password1", sid, 30));
        return authService.login(new LoginRequestDTO(name, "Password1", sid)).authToken();
    }

    private static CardDetailsDTO card() {
        return new CardDetailsDTO("4111111111111111", "123", 12, 2030, "Demo Buyer");
    }

    private static void assertMissingPermission(Executable action, String permissionName) {
        RuntimeException ex = assertThrows(RuntimeException.class, action);
        String msg = String.valueOf(rootMessage(ex));
        assertTrue(msg.contains("Missing permission") && msg.contains(permissionName),
                "expected a missing-" + permissionName + " rejection, but was: " + msg);
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getMessage();
    }
}
