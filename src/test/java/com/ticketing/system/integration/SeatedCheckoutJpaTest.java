package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.catalog.domain.Seat;
import com.ticketing.system.catalog.domain.SeatStatus;
import com.ticketing.system.catalog.domain.SeatedZone;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.integration.SeatedShowFixture.Show;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CardDetailsDTO;
import com.ticketing.system.shared.dto.CheckoutResultDTO;
import com.ticketing.system.shared.dto.InventoryZoneDTO;

/**
 * UC-10 on the Jpa adapters: a checkout turns the seat its cart holds from RESERVED into SOLD, and that is what
 * the database keeps (NaimElijah/Ticketeer#189). A zone's available count reads a held seat and a sold one alike, so this reloads
 * the seat itself and reads the zone's sold and reserved counts. Runs on H2.
 */
@SpringBootTest
@ActiveProfiles("test")
class SeatedCheckoutJpaTest {

    private static final String SEAT = SeatedShowFixture.SEATS.get(0);
    private static final String CURRENCY = "ILS";
    private static final CardDetailsDTO CARD = new CardDetailsDTO("4111111111111234", "123", 12, 2030, "Test Holder");
    // Unique to this class: the context, and with it the checkout's idempotency cache, is shared.
    private static final String IDEMPOTENCY_KEY = "seated-checkout-jpa-1";

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private CatalogService catalogService;
    @Autowired private SystemAdminService systemAdminService;
    @Autowired private ReservationService reservationService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private EventRepository eventRepository;
    @Autowired private ActiveOrderRepository activeOrderRepository;
    @Autowired private OrderReceiptRepository orderReceiptRepository;
    @Autowired private Clock clock;

    @Test
    void GivenAReservedSeat_WhenTheMemberChecksOut_ThenTheSeatIsSoldAndHeldByNoOrder() {
        SeatedShowFixture fixture = new SeatedShowFixture(authService, companyService, eventManagementService,
                catalogService, systemAdminService, clock);
        fixture.ensureMarketOpen();
        Show show = fixture.publishSeatedShow();
        AuthTokenDTO member = fixture.registerAndLogin("seatedCheckoutMember");
        reservationService.reserveForMember(member.token(), show.eventId(), show.zoneId(),
                InventorySelectionDTO.seated(List.of(SEAT)));

        CheckoutResultDTO result = checkoutService.checkoutMember(member.token(), IDEMPOTENCY_KEY, CURRENCY, CARD);

        Seat seat = ((SeatedZone) eventRepository.findById(show.eventId()).getVenueMap().getZone(show.zoneId()))
                .getSeatByLabel(SEAT);
        InventoryZoneDTO zone = fixture.zoneOf(show);
        assertAll(
                () -> assertEquals(SeatStatus.SOLD, seat.getStatus(), "the bought seat must be sold"),
                () -> assertNull(seat.getReservedByOrderKey(), "a sold seat is held by no order"),
                () -> assertEquals(1, zone.getSoldAmount(), "the zone must count the seat as sold"),
                () -> assertEquals(0, zone.getReservedAmount(), "the zone must hold no seat"),
                () -> assertEquals(List.of(result.orderReceiptId()),
                        orderReceiptRepository.findByEventId(show.eventId()).stream().map(OrderReceipt::getId).toList(),
                        "the purchase must leave its receipt"),
                () -> assertNull(activeOrderRepository.getByUserId(member.userId()), "the bought cart must be gone"));
    }
}
