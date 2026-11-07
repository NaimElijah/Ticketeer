package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.integration.SeatedShowFixture.Show;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.domain.CartLineItem;
import com.ticketing.system.shared.dto.AuthTokenDTO;

/**
 * Business rule 11 in {@code requirements.md}: an active order belongs to its buyer, and nobody else can change
 * it before it expires. Ownership is structural, since every change is made to the order the caller's own
 * session looks up, so these tests drive two members' orders through {@link ReservationService} on the Jpa
 * adapters and check that one member's changes never reach the other's order or the seats it holds.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderOwnershipJpaTest {

    private static final String FIRST_BUYERS_SEAT = SeatedShowFixture.SEATS.get(0);
    private static final String SECOND_BUYERS_SEAT = SeatedShowFixture.SEATS.get(1);

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private CatalogService catalogService;
    @Autowired private SystemAdminService systemAdminService;
    @Autowired private ReservationService reservationService;
    @Autowired private ActiveOrderRepository activeOrderRepository;
    @Autowired private Clock clock;

    private SeatedShowFixture fixture;
    private Show show;
    private AuthTokenDTO firstBuyer;
    private AuthTokenDTO secondBuyer;

    @BeforeEach
    void eachBuyerHoldsOneSeat() {
        fixture = new SeatedShowFixture(authService, companyService, eventManagementService, catalogService,
                systemAdminService, clock);
        fixture.ensureMarketOpen();
        show = fixture.publishSeatedShow();
        firstBuyer = fixture.registerAndLogin("firstBuyer");
        secondBuyer = fixture.registerAndLogin("secondBuyer");
        reservationService.reserveForMember(firstBuyer.token(), show.eventId(), show.zoneId(), seat(FIRST_BUYERS_SEAT));
        reservationService.reserveForMember(secondBuyer.token(), show.eventId(), show.zoneId(),
                seat(SECOND_BUYERS_SEAT));
    }

    @Test
    void GivenAnotherBuyersSession_WhenChangeOrder_ThenOnlyTheCallersOrderChanges() {
        reservationService.removeForMember(secondBuyer.token(), show.eventId(), show.zoneId(), seat(SECOND_BUYERS_SEAT));

        assertAll(
                () -> assertEquals(List.of(), seatsHeldBy(secondBuyer), "the caller's own seat leaves its order"),
                () -> assertEquals(List.of(FIRST_BUYERS_SEAT), seatsHeldBy(firstBuyer),
                        "the other buyer's order is untouched"),
                () -> assertEquals(SeatedShowFixture.SEATS.size() - 1, fixture.availableSeats(show),
                        "only the caller's seat is back in stock"));
    }

    @Test
    void GivenAnotherBuyersSeat_WhenRemoveForMember_ThenRefusedAndBothOrdersUnchanged() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> reservationService.removeForMember(secondBuyer.token(), show.eventId(), show.zoneId(),
                        seat(FIRST_BUYERS_SEAT)));

        assertAll(
                () -> assertEquals("Active order does not contain seat: " + FIRST_BUYERS_SEAT, refused.getMessage()),
                () -> assertEquals(List.of(FIRST_BUYERS_SEAT), seatsHeldBy(firstBuyer),
                        "the seat stays in its buyer's order"),
                () -> assertEquals(List.of(SECOND_BUYERS_SEAT), seatsHeldBy(secondBuyer),
                        "the caller's own order is untouched by its refused change"),
                () -> assertEquals(SeatedShowFixture.SEATS.size() - 2, fixture.availableSeats(show),
                        "neither seat is released"));
    }

    private List<String> seatsHeldBy(AuthTokenDTO buyer) {
        ActiveOrder order = activeOrderRepository.getByUserId(buyer.userId());
        return order == null ? List.of() : order.getItems().stream().map(CartLineItem::getSeatNumber).toList();
    }

    private static InventorySelectionDTO seat(String seat) {
        return InventorySelectionDTO.seated(List.of(seat));
    }
}
