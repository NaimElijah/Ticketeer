package com.ticketing.system.acceptance;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.shared.dto.AppointmentResponseDTO;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CompanyRegistrationDTO;
import com.ticketing.system.catalog.application.dto.EventCreationDTO;
import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.shared.dto.EventPolicyConfigDTO;
import com.ticketing.system.shared.dto.GridPlacementDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.organization.application.dto.ManagerAppointmentRequestDTO;
import com.ticketing.system.shared.dto.OwnerAppointmentRequestDTO;
import com.ticketing.system.shared.dto.PurchasePolicyDTO;
import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO.ZoneConfigDTO;
import com.ticketing.system.shared.dto.ZoneDetailDTO;
import com.ticketing.system.shared.persistence.PersistenceProfiles;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.organization.application.service.CompanyMembershipService;
import com.ticketing.system.catalog.application.service.EventManagementService;
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
 *   <li>a published event is locked against layout changes by its status: only a DRAFT or SCHEDULED
 *       event's map may change, so tickets on sale are never orphaned by a reconfiguration. The guard
 *       against a map with reserved or sold places is behind that one: a place can be held only while
 *       the event is on sale, and no event goes back to DRAFT or SCHEDULED.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles({"test", PersistenceProfiles.MEMORY})
class RoleChainAndLayoutPermissionAcceptanceTest {

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private CompanyMembershipService companyMembershipService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private ProductionCompanyRepository companyRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private Clock clock;

    @Test
    void GivenFounderAppointsOwnerAppointsManager_WhenCheckingRolesAndPermissions_ThenChainAndGrantedPermissionsHold() {
        Chain c = buildChain();

        assertEquals(c.u1Id, companyRepository.getCompanyById(c.companyId).getFounderId(),
                "u1 is the founder");
        assertTrue(companyRepository.getCompanyById(c.companyId).isOwner(c.u2Id),
                "u2 (appointed by u1) is an owner");
        assertTrue(companyMembershipService.hasPermissionInCompany(c.u3Id, c.companyId, Permission.CONFIGURE_VENUE),
                "u3 (appointed by u2) has the granted CONFIGURE_VENUE permission");
        assertFalse(companyMembershipService.hasPermissionInCompany(c.u3Id, c.companyId, Permission.EDIT_POLICIES),
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
        assertEquals(120, producerStandingZone(c.u3.token(), eventId).capacity(),
                "u3's layout edit took effect");

        // u3 cannot manage purchase policies (no EDIT_POLICIES) — the policy path is blocked.
        PurchasePolicyDTO maxPolicy = new PurchasePolicyDTO("MAX_TICKETS", null, null, 4, null);
        assertMissingPermission(
                () -> eventManagementService.setEventPolicies(
                        c.u3.token(), new EventPolicyConfigDTO(c.companyId, eventId, maxPolicy)),
                "EDIT_POLICIES");
    }

    @Test
    void GivenAnOnSaleEvent_WhenLayoutChangeAttempted_ThenRefusedByStatusAndTheMapIsUnchanged() {
        Chain c = buildChain();
        int eventId = createScheduledStandingEvent(c.u1, c.companyId, "Published Event", 100, 50.0);
        eventManagementService.publishEvent(c.u1.token(), c.companyId, eventId);

        ZoneConfigDTO replacement = new ZoneConfigDTO("Replacement", false, 10, null, 50.0,
                new GridPlacementDTO(1, 1, 1, 1));
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> eventManagementService.configureVenueMap(c.u3.token(), c.companyId,
                        new VenueMapConfigDTO(String.valueOf(eventId), "Hacked Arena", 1, 1, List.of(replacement))));

        assertEquals("Inventory can only be edited while event is DRAFT or SCHEDULED", refused.getMessage());
        ZoneDetailDTO zone = producerStandingZone(c.u1.token(), eventId);
        assertAll("the published event keeps its map",
                () -> assertEquals("Standing", zone.name()),
                () -> assertEquals(100, zone.capacity()));
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
                List.of(new ShowDate(LocalDateTime.now(clock).plusDays(10), LocalDateTime.now(clock).plusDays(10).plusHours(3))),
                ZoneId.of("Asia/Jerusalem"),
                null));
        int eventId = Integer.parseInt(ev.eventId());
        ZoneConfigDTO zone = new ZoneConfigDTO("Standing", false, capacity, null, price,
                new GridPlacementDTO(1, 1, 1, 1));
        eventManagementService.configureVenueMap(owner.token(), companyId,
                new VenueMapConfigDTO(ev.eventId(), "Arena", 1, 1, List.of(zone)));
        return eventId;
    }

    /** The producer's read of a standing zone; the public catalog hides an unpublished event's map (#185). */
    private ZoneDetailDTO producerStandingZone(String token, int eventId) {
        return eventManagementService.getEventZones(token, eventId).zones().stream()
                .filter(z -> !z.seated())
                .findFirst().orElseThrow();
    }

    private AuthTokenDTO registerAndLogin(String baseName) {
        String name = baseName + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String sid = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO(name, name + "@test.com", "Password1", sid, 30));
        return authService.login(new LoginRequestDTO(name, "Password1", sid)).authToken();
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
