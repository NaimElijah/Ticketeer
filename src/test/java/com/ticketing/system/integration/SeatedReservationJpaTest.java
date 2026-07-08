package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.Core.Application.dto.AuthTokenDTO;
import com.ticketing.system.Core.Application.dto.CompanyRegistrationDTO;
import com.ticketing.system.Core.Application.dto.EventCreationDTO;
import com.ticketing.system.Core.Application.dto.EventDetailDTO;
import com.ticketing.system.Core.Application.dto.GridPlacementDTO;
import com.ticketing.system.Core.Application.dto.InventorySelectionDTO;
import com.ticketing.system.Core.Application.dto.InventoryZoneDTO;
import com.ticketing.system.Core.Application.dto.LoginRequestDTO;
import com.ticketing.system.Core.Application.dto.MarketControlRequestDTO;
import com.ticketing.system.Core.Application.dto.RegisterRequestDTO;
import com.ticketing.system.Core.Application.dto.ReservationResultDTO;
import com.ticketing.system.Core.Application.dto.VenueMapConfigDTO;
import com.ticketing.system.Core.Application.dto.VenueMapConfigDTO.SeatConfigDTO;
import com.ticketing.system.Core.Application.dto.VenueMapConfigDTO.ZoneConfigDTO;
import com.ticketing.system.Core.Application.dto.VenueMapDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.Core.Application.services.CatalogService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.Core.Application.services.EventManagementService;
import com.ticketing.system.Core.Application.services.ReservationService;
import com.ticketing.system.Core.Application.services.SystemAdminService;
import com.ticketing.system.Core.Domain.events.EventCategory;
import com.ticketing.system.Core.Domain.events.EventStatus;
import com.ticketing.system.Core.Domain.events.IEventRepository;
import com.ticketing.system.Core.Domain.events.Location;
import com.ticketing.system.Core.Domain.events.ShowDate;
import com.ticketing.system.Infrastructure.scheduling.EventCompletionSweeper;

/**
 * Regression test for the seated-ticket reservation failure under the {@code jpa} profile.
 *
 * <p>A buyer reserving free, addressable seats flips several child {@code Seat} rows
 * (AVAILABLE → RESERVED, plus the holding order key + expiry) which must persist through the
 * {@code SeatedZone} {@code @OneToMany} seat map. The default in-memory ({@code !jpa}) suite can't
 * catch a flush/merge or optimistic-lock problem there, and the existing JPA repo test only saves
 * <em>AVAILABLE</em> seats — so this drives a real {@code reserve} under {@code {"test","jpa"}}
 * (real {@code Jpa*Repository} + H2, in-process external stubs). Fails before the fix; passes after.
 */
@SpringBootTest
@ActiveProfiles({"test", "jpa"})
class SeatedReservationJpaTest {

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private ReservationService reservationService;
    @Autowired private CatalogService catalogService;
    @Autowired private SystemAdminService systemAdminService;
    @Autowired private EventCompletionSweeper eventCompletionSweeper;
    @Autowired private IEventRepository eventRepository;

    @Test
    void memberReservesTwoFreeSeats_underJpa_succeeds() {
        ensureMarketOpen();

        AuthTokenDTO owner = registerAndLogin("seatOwner");
        int companyId = companyService.registerCompany(
                owner.token(), new CompanyRegistrationDTO("SeatCo", "desc")).companyId();
        int eventId = createPublishedSeatedEvent(owner, companyId);
        int zoneId = seatedZone(owner.token(), eventId).getId();

        AuthTokenDTO buyer = registerAndLogin("seatBuyer");
        ReservationResultDTO result = reservationService.reserveForMember(
                buyer.token(), eventId, zoneId, InventorySelectionDTO.seated(List.of("A1", "A2")));

        assertNotNull(result, "reserving two free seats must return a result, not throw");
        assertEquals(1, seatedZone(buyer.token(), eventId).getAvailableAmount(),
                "2 of the 3 seats are now held");
    }

    @Test
    void publishedFutureEvent_survivesTheCompletionSweep_andStaysReservable() {
        ensureMarketOpen();
        AuthTokenDTO owner = registerAndLogin("sweepOwner");
        int companyId = companyService.registerCompany(
                owner.token(), new CompanyRegistrationDTO("SweepCo", "desc")).companyId();
        int eventId = createPublishedSeatedEvent(owner, companyId);

        // The dev @Scheduled completion sweep runs ~60s after boot, before a buyer reserves. A
        // future-dated event must survive it (only events whose last show has ended get COMPLETED).
        eventCompletionSweeper.completeFinishedEvents(java.time.LocalDateTime.now(java.time.Clock.systemUTC()));

        assertEquals(EventStatus.ON_SALE, eventRepository.findById(eventId).getStatus(),
                "a future-dated event must NOT be auto-completed by the sweeper");

        AuthTokenDTO buyer = registerAndLogin("sweepBuyer");
        assertNotNull(reservationService.reserveForMember(buyer.token(), eventId,
                seatedZone(buyer.token(), eventId).getId(), InventorySelectionDTO.seated(List.of("A1", "A2"))),
                "the event is still ON_SALE after the sweep, so reserve must succeed");
    }

    // ---- helpers --------------------------------------------------------

    private void ensureMarketOpen() {
        if (systemAdminService.isMarketOpen()) {
            return;
        }
        systemAdminService.initializePlatform(); // idempotent — creates the default admin + READY
        AuthTokenDTO admin = authService.signInAsAdmin("admin", "admin");
        systemAdminService.openMarket(new MarketControlRequestDTO("OPEN", "seated reserve jpa test", admin.token()));
    }

    /** addEvent + configure a 3-seat seated zone (A1/A2/A3) + publish → ON_SALE. */
    private int createPublishedSeatedEvent(AuthTokenDTO owner, int companyId) {
        EventDetailDTO ev = eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                companyId, "Seated Show", "desc", List.of("Artist"), EventCategory.CONCERT, 4.5,
                new Location("Israel", "Tel Aviv"),
                List.of(new ShowDate(LocalDateTime.now().plusDays(10), LocalDateTime.now().plusDays(10).plusHours(3))),
                null));
        int eventId = Integer.parseInt(ev.eventId());
        List<SeatConfigDTO> seats = List.of(
                new SeatConfigDTO("A1", 1, 0),
                new SeatConfigDTO("A2", 2, 0),
                new SeatConfigDTO("A3", 3, 0));
        ZoneConfigDTO seated = new ZoneConfigDTO("Orchestra", true, null, seats, 100.0,
                new GridPlacementDTO(1, 1, 1, 1));
        eventManagementService.configureVenueMap(owner.token(), companyId,
                new VenueMapConfigDTO(ev.eventId(), "Arena", 1, 1, List.of(seated)));
        eventManagementService.publishEvent(owner.token(), companyId, eventId);
        return eventId;
    }

    private InventoryZoneDTO seatedZone(String token, int eventId) {
        VenueMapDTO map = catalogService.getEventVenueMap(token, eventId);
        return map.inventoryZones().stream()
                .filter(z -> "SEATED".equals(z.getZoneType()))
                .findFirst().orElseThrow();
    }

    private AuthTokenDTO registerAndLogin(String baseName) {
        String name = baseName + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String sid = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO(name, name + "@test.com", "Password1", sid, 30));
        return authService.login(new LoginRequestDTO(name, "Password1", sid)).authToken();
    }
}
