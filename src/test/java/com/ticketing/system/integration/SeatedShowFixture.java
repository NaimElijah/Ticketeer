package com.ticketing.system.integration;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.ticketing.system.catalog.application.dto.EventCreationDTO;
import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CompanyRegistrationDTO;
import com.ticketing.system.shared.dto.GridPlacementDTO;
import com.ticketing.system.shared.dto.InventoryZoneDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.shared.dto.MarketControlRequestDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO.SeatConfigDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO.ZoneConfigDTO;

/**
 * Arranges a market-open platform with one published show of {@link #SEATS} seats, through the real
 * application services of a {@code test}-profile context. A plain class rather than a bean, so no other
 * Spring context picks it up. Show dates come from the context's {@link Clock} (ADR-008).
 */
final class SeatedShowFixture {

    static final String PASSWORD = "Password1";
    static final int MEMBER_AGE = 30;
    static final List<String> SEATS = List.of("A1", "A2", "A3");

    private static final ZoneId VENUE_ZONE = ZoneId.of("Asia/Jerusalem");
    private static final int DAYS_UNTIL_SHOW = 10;
    private static final int SHOW_HOURS = 3;
    private static final double SEAT_PRICE = 100.0;
    private static final int UNIQUE_SUFFIX_LENGTH = 8;

    private final AuthenticationService authService;
    private final CompanyManagementService companyService;
    private final EventManagementService eventManagementService;
    private final CatalogService catalogService;
    private final SystemAdminService systemAdminService;
    private final Clock clock;

    SeatedShowFixture(AuthenticationService authService, CompanyManagementService companyService,
            EventManagementService eventManagementService, CatalogService catalogService,
            SystemAdminService systemAdminService, Clock clock) {
        this.authService = authService;
        this.companyService = companyService;
        this.eventManagementService = eventManagementService;
        this.catalogService = catalogService;
        this.systemAdminService = systemAdminService;
        this.clock = clock;
    }

    /** The show's id and its one seated zone, and an owner token able to read its venue map. */
    record Show(int eventId, int zoneId, String ownerToken) {
    }

    /** Opens the market if needed (idempotent init with the default admin, as the other Jpa tests do). */
    void ensureMarketOpen() {
        if (systemAdminService.isMarketOpen()) {
            return;
        }
        systemAdminService.initializePlatform();
        AuthTokenDTO admin = authService.signInAsAdmin("admin", "admin");
        systemAdminService.openMarket(new MarketControlRequestDTO("OPEN", "sales jpa test", admin.token()));
    }

    /** A new company with one ON_SALE show whose single seated zone holds {@link #SEATS}. */
    Show publishSeatedShow() {
        List<SeatConfigDTO> seats = List.of(
                new SeatConfigDTO(SEATS.get(0), 1, 0),
                new SeatConfigDTO(SEATS.get(1), 2, 0),
                new SeatConfigDTO(SEATS.get(2), 3, 0));
        return publishShow(new ZoneConfigDTO("Orchestra", true, null, seats, SEAT_PRICE,
                new GridPlacementDTO(1, 1, 1, 1)));
    }

    /** A published show whose one zone is standing, with room for {@code capacity}. */
    Show publishStandingShow(int capacity) {
        return publishShow(new ZoneConfigDTO("Floor", false, capacity, null, SEAT_PRICE,
                new GridPlacementDTO(1, 1, 1, 1)));
    }

    private Show publishShow(ZoneConfigDTO onlyZone) {
        AuthTokenDTO owner = registerAndLogin("showOwner");
        int companyId = companyService.registerCompany(
                owner.token(), new CompanyRegistrationDTO("Co-" + uniqueSuffix(), "desc")).companyId();
        LocalDateTime showStart = LocalDateTime.now(clock.withZone(VENUE_ZONE)).plusDays(DAYS_UNTIL_SHOW);
        EventDetailDTO event = eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                companyId, "Seated Show", "desc", List.of("Artist"), EventCategory.CONCERT, 4.5,
                new Location("Israel", "Tel Aviv"),
                List.of(new ShowDate(showStart, showStart.plusHours(SHOW_HOURS))),
                VENUE_ZONE,
                null));
        eventManagementService.configureVenueMap(owner.token(), companyId,
                new VenueMapConfigDTO(event.eventId(), "Arena", 1, 1, List.of(onlyZone)));
        int eventId = Integer.parseInt(event.eventId());
        eventManagementService.publishEvent(owner.token(), companyId, eventId);
        return new Show(eventId, zone(owner.token(), eventId).getId(), owner.token());
    }

    /** Seats of the show nobody holds or has bought. */
    int availableSeats(Show show) {
        return zone(show.ownerToken(), show.eventId()).getAvailableAmount();
    }

    /** Registers a member from a fresh guest session; the name is made unique from {@code baseName}. */
    String register(String baseName) {
        String name = baseName + "-" + uniqueSuffix();
        String sessionId = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO(name, name + "@test.com", PASSWORD, sessionId, MEMBER_AGE));
        return name;
    }

    /** Logs {@code username} in from a fresh guest session. */
    AuthTokenDTO login(String username) {
        String sessionId = authService.startGuestSession().sessionId();
        return authService.login(new LoginRequestDTO(username, PASSWORD, sessionId)).authToken();
    }

    AuthTokenDTO registerAndLogin(String baseName) {
        return login(register(baseName));
    }

    private InventoryZoneDTO zone(String token, int eventId) {
        // Every show this fixture publishes has exactly one zone, seated or standing.
        return catalogService.getEventVenueMap(token, eventId).inventoryZones().stream()
                .findFirst().orElseThrow();
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, UNIQUE_SUFFIX_LENGTH);
    }
}
