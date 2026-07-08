package com.ticketing.system.acceptance;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.Core.Application.dto.AppointmentResponseDTO;
import com.ticketing.system.Core.Application.dto.AuthTokenDTO;
import com.ticketing.system.Core.Application.dto.CompanyPolicyConfigDTO;
import com.ticketing.system.Core.Application.dto.CompanyRegistrationDTO;
import com.ticketing.system.Core.Application.dto.EventCreationDTO;
import com.ticketing.system.Core.Application.dto.EventDetailDTO;
import com.ticketing.system.Core.Application.dto.EventPolicyConfigDTO;
import com.ticketing.system.Core.Application.dto.EventUpdateDTO;
import com.ticketing.system.Core.Application.dto.GridPlacementDTO;
import com.ticketing.system.Core.Application.dto.LoginRequestDTO;
import com.ticketing.system.Core.Application.dto.ManagerAppointmentRequestDTO;
import com.ticketing.system.Core.Application.dto.PurchasePolicyDTO;
import com.ticketing.system.Core.Application.dto.RegisterRequestDTO;
import com.ticketing.system.Core.Application.dto.VenueMapConfigDTO;
import com.ticketing.system.Core.Application.dto.VenueMapConfigDTO.ZoneConfigDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.Core.Application.services.CompanyManagementService;
import com.ticketing.system.Core.Application.services.EventManagementService;
import com.ticketing.system.Core.Domain.events.EventCategory;
import com.ticketing.system.Core.Domain.events.EventStatus;
import com.ticketing.system.Core.Domain.events.Location;
import com.ticketing.system.Core.Domain.events.ShowDate;
import com.ticketing.system.Core.Domain.users.Permission;

/**
 * The manager-permission matrix end-to-end (real services, real appointment → permission
 * resolution). Each manager permission grants exactly its own actions and nothing else:
 * <ul>
 *   <li>{@code MANAGE_INVENTORY} — edit metadata, change status, create events; NOT venue/policies.</li>
 *   <li>{@code CONFIGURE_VENUE} — edit the venue map; NOT event metadata/status/policies.</li>
 *   <li>{@code EDIT_POLICIES} — edit company + event purchase policies; NOT event/venue.</li>
 * </ul>
 * Guards the re-gating of the event-management service methods from CONFIGURE_VENUE to the
 * intended permission.
 */
@SpringBootTest
@ActiveProfiles("test")
class ManagerPermissionMatrixAcceptanceTest {

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;

    @Test
    void ConfigureVenueManager_CanEditVenue_ButNotEventOrPolicies() {
        Fixture f = setup("venue");
        AuthTokenDTO m = appointManagerWith(f, "venueMgr", Permission.CONFIGURE_VENUE);

        // CAN: reconfigure the venue map.
        eventManagementService.configureVenueMap(m.token(), f.companyId,
                new VenueMapConfigDTO(String.valueOf(f.eventId), "Arena", 1, 1, List.of(standingZone("Reworked", 80))));

        // CANNOT: edit metadata / change status (MANAGE_INVENTORY) or policies (EDIT_POLICIES).
        assertMissingPermission(() -> eventManagementService.editEventDetails(m.token(),
                new EventUpdateDTO(String.valueOf(f.eventId), "Hacked", null, null, null, null)), "MANAGE_INVENTORY");
        assertMissingPermission(() -> eventManagementService.changeEventStatus(m.token(), f.eventId, EventStatus.ON_SALE),
                "MANAGE_INVENTORY");
        assertMissingPermission(() -> eventManagementService.setEventPolicies(m.token(),
                new EventPolicyConfigDTO(f.companyId, f.eventId, maxTickets(4))), "EDIT_POLICIES");
    }

    @Test
    void InventoryManager_CanManageEvents_ButNotVenueOrPolicies() {
        Fixture f = setup("inv");
        AuthTokenDTO m = appointManagerWith(f, "invMgr", Permission.MANAGE_INVENTORY);

        // CAN: edit metadata, change status (Scheduled -> On sale), create a new event.
        eventManagementService.editEventDetails(m.token(),
                new EventUpdateDTO(String.valueOf(f.eventId), "Renamed by Manager", null, null, null, null));
        eventManagementService.changeEventStatus(m.token(), f.eventId, EventStatus.ON_SALE);
        EventDetailDTO created = eventManagementService.addEvent(m.token(), new EventCreationDTO(
                f.companyId, "Manager-Created", "desc", List.of("Artist"), EventCategory.CONCERT, 4.5,
                new Location("Israel", "Tel Aviv"),
                List.of(new ShowDate(LocalDateTime.now().plusDays(5), LocalDateTime.now().plusDays(5).plusHours(2))),
                null));
        assertNotNull(created.eventId(), "a MANAGE_INVENTORY manager can create events");

        // CANNOT: venue (CONFIGURE_VENUE) or policies (EDIT_POLICIES).
        assertMissingPermission(() -> eventManagementService.configureVenueMap(m.token(), f.companyId,
                new VenueMapConfigDTO(String.valueOf(f.eventId), "X", 1, 1, List.of(standingZone("Z", 10)))),
                "CONFIGURE_VENUE");
        assertMissingPermission(() -> eventManagementService.setEventPolicies(m.token(),
                new EventPolicyConfigDTO(f.companyId, f.eventId, maxTickets(4))), "EDIT_POLICIES");
    }

    @Test
    void PoliciesManager_CanEditPolicies_ButNotEventsOrVenue() {
        Fixture f = setup("pol");
        AuthTokenDTO m = appointManagerWith(f, "polMgr", Permission.EDIT_POLICIES);

        // CAN: event-level and company-level purchase policies.
        eventManagementService.setEventPolicies(m.token(),
                new EventPolicyConfigDTO(f.companyId, f.eventId, maxTickets(4)));
        companyService.setCompanyPolicies(m.token(),
                new CompanyPolicyConfigDTO(f.companyId,
                        new PurchasePolicyDTO("MIN_TICKETS", null, 2, null, null), List.of()));

        // CANNOT: edit metadata (MANAGE_INVENTORY) or venue (CONFIGURE_VENUE).
        assertMissingPermission(() -> eventManagementService.editEventDetails(m.token(),
                new EventUpdateDTO(String.valueOf(f.eventId), "Nope", null, null, null, null)), "MANAGE_INVENTORY");
        assertMissingPermission(() -> eventManagementService.configureVenueMap(m.token(), f.companyId,
                new VenueMapConfigDTO(String.valueOf(f.eventId), "X", 1, 1, List.of(standingZone("Z", 10)))),
                "CONFIGURE_VENUE");
    }

    // ---- fixtures & helpers --------------------------------------------

    private record Fixture(AuthTokenDTO owner, int companyId, int eventId) { }

    /** Owner + company + a SCHEDULED standing-zone event (created by the owner, who holds all perms). */
    private Fixture setup(String prefix) {
        AuthTokenDTO owner = registerAndLogin(prefix + "Owner");
        int companyId = companyService.registerCompany(owner.token(),
                new CompanyRegistrationDTO("MatrixCo-" + owner.userId(), "desc")).companyId();
        EventDetailDTO ev = eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                companyId, "Matrix Event", "desc", List.of("Artist"), EventCategory.CONCERT, 4.5,
                new Location("Israel", "Tel Aviv"),
                List.of(new ShowDate(LocalDateTime.now().plusDays(10), LocalDateTime.now().plusDays(10).plusHours(3))),
                null));
        int eventId = Integer.parseInt(ev.eventId());
        eventManagementService.configureVenueMap(owner.token(), companyId,
                new VenueMapConfigDTO(ev.eventId(), "Arena", 1, 1, List.of(standingZone("Standing", 100))));
        return new Fixture(owner, companyId, eventId);
    }

    private AuthTokenDTO appointManagerWith(Fixture f, String baseName, Permission... perms) {
        AuthTokenDTO mgr = registerAndLogin(baseName);
        companyService.appointManager(f.owner.token(),
                new ManagerAppointmentRequestDTO(f.companyId, mgr.userId(), List.of(perms)));
        companyService.respondToAppointment(mgr.token(), new AppointmentResponseDTO(f.companyId, true));
        return mgr;
    }

    private static ZoneConfigDTO standingZone(String name, int capacity) {
        return new ZoneConfigDTO(name, false, capacity, null, 50.0, new GridPlacementDTO(1, 1, 1, 1));
    }

    private static PurchasePolicyDTO maxTickets(int max) {
        return new PurchasePolicyDTO("MAX_TICKETS", null, null, max, null);
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
