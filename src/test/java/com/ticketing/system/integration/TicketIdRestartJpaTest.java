package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.catalog.application.dto.EventCreationDTO;
import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.sales.adapter.out.persistence.SpringDataTicketRepository;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CardDetailsDTO;
import com.ticketing.system.shared.dto.CheckoutResultDTO;
import com.ticketing.system.shared.dto.CompanyRegistrationDTO;
import com.ticketing.system.shared.dto.GridPlacementDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.shared.dto.MarketControlRequestDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO.ZoneConfigDTO;
import com.ticketing.system.shared.dto.VenueMapDTO;

/**
 * Regression for #19 (V3 Req 7, persistence): ticket ids must not restart with the process.
 *
 * <p>Ticket rows written by a "previous boot" are inserted straight into the table before the
 * application mints any ticket id, then a real member checkout runs through the live services
 * on the Jpa backend. The dedicated in-memory database URL gives this class its own Spring
 * context, so the ticket repository's id sequence is unseeded and the ticket issuer is fresh —
 * exactly the state of a restarted process pointing at a populated database.
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:ticketidrestart;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@ActiveProfiles({"test", "jpa"})
@DirtiesContext
class TicketIdRestartJpaTest {

    private static final int PREVIOUS_BOOT_TICKETS = 5;
    private static final int TICKETS_BOUGHT = 2;
    private static final double STANDING_PRICE = 50.0;

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private CatalogService catalogService;
    @Autowired private ReservationService reservationService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private SystemAdminService systemAdminService;
    @Autowired private SpringDataTicketRepository ticketRows;

    @Test
    void GivenTicketsPersistedByAPreviousBoot_WhenMemberChecksOut_ThenNewTicketIdsDoNotCollide() {
        ticketRows.saveAllAndFlush(IntStream.rangeClosed(1, PREVIOUS_BOOT_TICKETS)
                .mapToObj(id -> new Ticket(999, 1, 999, null, 10.0, id, "LEGACY-" + id))
                .toList());
        openMarket();
        AuthTokenDTO owner = registerAndLogin("restartOwner");
        StandingEvent event = publishedStandingEvent(owner);
        AuthTokenDTO buyer = registerAndLogin("restartBuyer");
        reservationService.reserveForMember(buyer.token(), event.eventId(), event.zoneId(),
                InventorySelectionDTO.standing(TICKETS_BOUGHT));

        CheckoutResultDTO result = checkoutService.checkoutMember(buyer.token(), "idem-restart", "ILS", card());

        List<Integer> issuedIds = result.issuedTicketIds();
        assertEquals(TICKETS_BOUGHT, Set.copyOf(issuedIds).size());
        assertTrue(issuedIds.stream().allMatch(id -> id > PREVIOUS_BOOT_TICKETS),
                "new ticket ids must lie above those persisted before the restart: " + issuedIds);
        Set<Integer> persistedIds = ticketRows.findByOrderReceiptId(result.orderReceiptId()).stream()
                .map(Ticket::getId)
                .collect(Collectors.toSet());
        assertEquals(Set.copyOf(issuedIds), persistedIds);
        assertEquals("LEGACY-1", ticketRows.findById(1).orElseThrow().getBarcode());
        assertEquals(PREVIOUS_BOOT_TICKETS + TICKETS_BOUGHT, ticketRows.count());
    }

    private void openMarket() {
        systemAdminService.initializePlatform();
        AuthTokenDTO admin = authService.signInAsAdmin("admin", "admin");
        systemAdminService.openMarket(new MarketControlRequestDTO("OPEN", "ticket id restart test", admin.token()));
    }

    private record StandingEvent(int eventId, int zoneId) { }

    /** The owner's company with one published event holding a single standing zone. */
    private StandingEvent publishedStandingEvent(AuthTokenDTO owner) {
        int companyId = companyService.registerCompany(
                owner.token(), new CompanyRegistrationDTO("RestartCo", "desc")).companyId();
        EventDetailDTO event = eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                companyId, "Restart Show", "desc", List.of("Artist"), EventCategory.CONCERT, 4.5,
                new Location("Israel", "Tel Aviv"),
                List.of(new ShowDate(LocalDateTime.now().plusDays(10), LocalDateTime.now().plusDays(10).plusHours(3))),
                null));
        int eventId = Integer.parseInt(event.eventId());
        ZoneConfigDTO standing = new ZoneConfigDTO(
                "Standing", false, 100, null, STANDING_PRICE, new GridPlacementDTO(1, 1, 1, 1));
        eventManagementService.configureVenueMap(owner.token(), companyId,
                new VenueMapConfigDTO(event.eventId(), "Restart Arena", 1, 1, List.of(standing)));
        eventManagementService.publishEvent(owner.token(), companyId, eventId);

        VenueMapDTO map = catalogService.getEventVenueMap(owner.token(), eventId);
        int zoneId = map.inventoryZones().stream()
                .filter(z -> "STANDING".equals(z.getZoneType()))
                .findFirst().orElseThrow().getId();
        return new StandingEvent(eventId, zoneId);
    }

    private AuthTokenDTO registerAndLogin(String name) {
        String sid = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO(name, name + "@test.com", "Password1", sid, 25));
        return authService.login(new LoginRequestDTO(name, "Password1", sid)).authToken();
    }

    private static CardDetailsDTO card() {
        return new CardDetailsDTO("4111111111111111", "123", 12, 2030, "Demo Buyer");
    }
}
