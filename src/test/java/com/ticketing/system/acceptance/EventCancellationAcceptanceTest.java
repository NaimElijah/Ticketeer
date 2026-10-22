package com.ticketing.system.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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
import com.ticketing.system.governance.application.service.SystemAnalyticsService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.reporting.application.service.CompanyAnalyticsService;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.RefundStatus;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.application.port.out.PaymentGateway;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.sales.domain.TransactionRecord;
import com.ticketing.system.sales.domain.TransactionRecord.TransactionType;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CardDetailsDTO;
import com.ticketing.system.shared.dto.CheckoutResultDTO;
import com.ticketing.system.shared.dto.CompanyDashboardDTO;
import com.ticketing.system.shared.dto.CompanyRegistrationDTO;
import com.ticketing.system.shared.dto.GridPlacementDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.shared.dto.MarketControlRequestDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO.ZoneConfigDTO;
import com.ticketing.system.shared.exception.RefundFailedException;
import com.ticketing.system.shared.persistence.PersistenceProfiles;

/**
 * UC-19 / UC-4 end to end: an owner cancels an event that has sold tickets, and the buyer is refunded
 * through the payment service (I.3). Drives the real reserve → checkout → cancel path against live
 * services (test profile: in-memory repositories and the in-process payment and issuer stubs). The
 * payment stub is wrapped in a spy so a test can make one refund fail and count the refunds per charge.
 */
@SpringBootTest
@ActiveProfiles({"test", PersistenceProfiles.MEMORY})
class EventCancellationAcceptanceTest {

    private static final double TICKET_PRICE = 50.0;
    private static final int TICKETS_BOUGHT = 2;

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private CatalogService catalogService;
    @Autowired private ReservationService reservationService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private SystemAdminService systemAdminService;
    @Autowired private OrderReceiptRepository orderReceiptRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private CompanyAnalyticsService companyAnalyticsService;
    @Autowired private SystemAnalyticsService systemAnalyticsService;

    @MockitoSpyBean
    private PaymentGateway paymentGateway;

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    /** Reservations and checkout require the market open; the test profile boots it closed. */
    @BeforeEach
    void ensureMarketOpen() {
        if (systemAdminService.isMarketOpen()) {
            return;
        }
        systemAdminService.initializePlatform();
        AuthTokenDTO admin = authService.signInAsAdmin("admin", "admin");
        systemAdminService.openMarket(new MarketControlRequestDTO("OPEN", "cancellation acceptance setup", admin.token()));
    }

    @Test
    void GivenPaidOrder_WhenOwnerCancelsTheEvent_ThenTheBuyerIsRefundedAndTheTicketsAreRefunded() {
        AuthTokenDTO owner = registerAndLogin("cancelOwner");
        Fixture event = newStandingEvent(owner);
        int receiptId = buyTickets(event);

        eventManagementService.cancelEventAndRefund(owner.token(), event.eventId());

        OrderReceipt receipt = orderReceiptRepository.findByOrderReceiptId(receiptId).orElseThrow();
        assertTrue(receipt.getReceiptLines().stream().allMatch(ReceiptLine::isRefunded));
        assertEquals(List.of(TICKET_PRICE * TICKETS_BOUGHT), refundAmounts(receipt));
        List<Ticket> tickets = ticketRepository.findByOrderReceiptId(receiptId);
        assertFalse(tickets.isEmpty());
        assertTrue(tickets.stream().allMatch(t -> t.getStatus() == TicketStatus.REFUNDED));
    }

    @Test
    void GivenCancelledAndRefundedEvent_WhenOwnerCancelsAgain_ThenNothingIsRefundedTwice() {
        AuthTokenDTO owner = registerAndLogin("cancelOwner");
        Fixture event = newStandingEvent(owner);
        int receiptId = buyTickets(event);
        eventManagementService.cancelEventAndRefund(owner.token(), event.eventId());

        eventManagementService.cancelEventAndRefund(owner.token(), event.eventId());

        OrderReceipt receipt = orderReceiptRepository.findByOrderReceiptId(receiptId).orElseThrow();
        assertEquals(List.of(TICKET_PRICE * TICKETS_BOUGHT), refundAmounts(receipt));
    }

    // #20 through catalog (review finding 6 on #129): the payment service declines one buyer's refund,
    // so the first cancel fails; cancelling again refunds that buyer, and nobody is refunded twice.
    @Test
    void GivenTheGatewayDeclinesARefund_WhenOwnerCancelsAgain_ThenEveryBuyerIsRefundedExactlyOnce() {
        AuthTokenDTO owner = registerAndLogin("cancelOwner");
        Fixture event = newStandingEvent(owner);
        CheckoutResultDTO declinedOrder = checkout(event);
        CheckoutResultDTO otherOrder = checkout(event);
        int declinedCharge = declinedOrder.paymentTransactionId();
        doThrow(new RefundFailedException(declinedCharge, "declined"))
                .doCallRealMethod()
                .when(paymentGateway).refund(eq(declinedCharge), anyDouble());

        assertThrows(RefundFailedException.class,
                () -> eventManagementService.cancelEventAndRefund(owner.token(), event.eventId()));
        eventManagementService.cancelEventAndRefund(owner.token(), event.eventId());

        verify(paymentGateway, times(2)).refund(eq(declinedCharge), anyDouble());
        verify(paymentGateway, times(1)).refund(eq(otherOrder.paymentTransactionId()), anyDouble());
        for (CheckoutResultDTO order : List.of(declinedOrder, otherOrder)) {
            OrderReceipt receipt = orderReceiptRepository.findByOrderReceiptId(order.orderReceiptId()).orElseThrow();
            assertTrue(receipt.getReceiptLines().stream().allMatch(ReceiptLine::isRefunded));
            assertEquals(List.of(TICKET_PRICE * TICKETS_BOUGHT), refundAmounts(receipt));
        }
    }

    // #21 end to end: one order holds two companies' tickets, and one company cancels its event. Only
    // that company's lines leave the revenue figures; the other company does not see the order refunded.
    @Test
    void GivenCrossCompanyOrder_WhenOneCompanyCancelsItsEvent_ThenOnlyItsLinesLeaveTheRevenueFigures() {
        AuthTokenDTO cancellingOwner = registerAndLogin("cancelOwner");
        AuthTokenDTO keepingOwner = registerAndLogin("keepOwner");
        Fixture cancelled = newStandingEvent(cancellingOwner);
        Fixture kept = newStandingEvent(keepingOwner);
        AuthTokenDTO buyer = registerAndLogin("crossBuyer");
        reservationService.reserveForMember(buyer.token(), cancelled.eventId(), cancelled.zoneId(),
                InventorySelectionDTO.standing(TICKETS_BOUGHT));
        reservationService.reserveForMember(buyer.token(), kept.eventId(), kept.zoneId(),
                InventorySelectionDTO.standing(TICKETS_BOUGHT));
        CheckoutResultDTO order = checkoutService.checkoutMember(buyer.token(), "idem-cancel-" + SEQ.incrementAndGet(),
                "ILS", new CardDetailsDTO("4111111111111111", "123", 12, 2030, "Demo Buyer"));
        double eachCompanysShare = TICKET_PRICE * TICKETS_BOUGHT;
        double platformRevenueBefore = systemAnalyticsService.adminOverview().revenue30d();

        eventManagementService.cancelEventAndRefund(cancellingOwner.token(), cancelled.eventId());

        CompanyDashboardDTO keptDashboard = companyAnalyticsService.dashboard(keepingOwner.token(), kept.companyId());
        assertEquals(TICKETS_BOUGHT, keptDashboard.ticketsSold30d());
        assertEquals(eachCompanysShare, keptDashboard.revenue30d());
        PurchaseRecordDTO keptRecord = companyAnalyticsService.salesHistory(keepingOwner.token(), kept.companyId())
                .records().get(0);
        assertEquals(RefundStatus.NONE, keptRecord.refundStatus());

        CompanyDashboardDTO cancelledDashboard =
                companyAnalyticsService.dashboard(cancellingOwner.token(), cancelled.companyId());
        assertEquals(0, cancelledDashboard.ticketsSold30d());
        assertEquals(0.0, cancelledDashboard.revenue30d());

        assertEquals(platformRevenueBefore - eachCompanysShare, systemAnalyticsService.adminOverview().revenue30d(), 1e-9);
        assertFalse(orderReceiptRepository.findByOrderReceiptId(order.orderReceiptId()).orElseThrow().isFullyRefunded());
    }

    // ---- helpers --------------------------------------------------------

    private record Fixture(int companyId, int eventId, int zoneId) { }

    private static List<Double> refundAmounts(OrderReceipt receipt) {
        return receipt.getTransactionRecords().stream()
                .filter(t -> t.getType() == TransactionType.REFUND)
                .map(TransactionRecord::getAmount)
                .toList();
    }

    /** A buyer reserves and checks out {@link #TICKETS_BOUGHT} standing tickets; returns the receipt id. */
    private int buyTickets(Fixture event) {
        return checkout(event).orderReceiptId();
    }

    /** A new buyer reserves and checks out {@link #TICKETS_BOUGHT} standing tickets. */
    private CheckoutResultDTO checkout(Fixture event) {
        AuthTokenDTO buyer = registerAndLogin("cancelBuyer");
        reservationService.reserveForMember(buyer.token(), event.eventId(), event.zoneId(),
                InventorySelectionDTO.standing(TICKETS_BOUGHT));
        return checkoutService.checkoutMember(buyer.token(), "idem-cancel-" + SEQ.incrementAndGet(),
                "ILS", new CardDetailsDTO("4111111111111111", "123", 12, 2030, "Demo Buyer"));
    }

    /** A company and a published single standing-zone event at {@link #TICKET_PRICE}. */
    private Fixture newStandingEvent(AuthTokenDTO owner) {
        int companyId = companyService.registerCompany(
                owner.token(), new CompanyRegistrationDTO("CancelCo" + SEQ.incrementAndGet(), "desc")).companyId();
        LocalDateTime start = LocalDateTime.now().plusDays(10);
        EventDetailDTO event = eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                companyId, "Cancelled Show", "desc", List.of("Artist"), EventCategory.CONCERT, 4.5,
                new Location("Israel", "Tel Aviv"), List.of(new ShowDate(start, start.plusHours(3))), ZoneId.of("Asia/Jerusalem"), null));
        int eventId = Integer.parseInt(event.eventId());
        ZoneConfigDTO standing = new ZoneConfigDTO("Standing", false, 100, null, TICKET_PRICE, new GridPlacementDTO(1, 1, 1, 1));
        eventManagementService.configureVenueMap(owner.token(), companyId,
                new VenueMapConfigDTO(event.eventId(), "Test Arena", 1, 1, List.of(standing)));
        eventManagementService.publishEvent(owner.token(), companyId, eventId);
        int zoneId = catalogService.getEventVenueMap(owner.token(), eventId).inventoryZones().stream()
                .filter(z -> "STANDING".equals(z.getZoneType()))
                .findFirst().orElseThrow().getId();
        return new Fixture(companyId, eventId, zoneId);
    }

    private AuthTokenDTO registerAndLogin(String baseName) {
        String name = baseName + SEQ.incrementAndGet();
        String sid = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO(name, name + "@test.com", "Password1", sid, 25));
        return authService.login(new LoginRequestDTO(name, "Password1", sid)).authToken();
    }
}
