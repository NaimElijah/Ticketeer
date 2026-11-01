package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.integration.SeatedShowFixture.Show;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.sales.application.service.RefundService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CardDetailsDTO;
import com.ticketing.system.shared.dto.CheckoutResultDTO;

/**
 * A member refund flips the order's tickets in one transaction of their own, after the refund has committed
 * (#187, ADR-018). When one flip fails, that transaction rolls back whole on the database: no ticket of the
 * order is left refunded while another stays valid, and the receipt's refund, already paid back, stands.
 * Runs on the Jpa adapters over H2.
 */
@SpringBootTest
@ActiveProfiles("test")
class MemberRefundTicketFlipFailureJpaTest {

    private static final List<String> BOUGHT_SEATS = SeatedShowFixture.SEATS.subList(0, 2);
    private static final String CURRENCY = "ILS";
    private static final CardDetailsDTO CARD = new CardDetailsDTO("4111111111111234", "123", 12, 2030, "Test Holder");

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private CatalogService catalogService;
    @Autowired private SystemAdminService systemAdminService;
    @Autowired private ReservationService reservationService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private RefundService refundService;
    @Autowired private OrderReceiptRepository orderReceiptRepository;
    @Autowired private Clock clock;

    // The Jpa adapter, spied on only to fail the refund's second ticket save.
    @MockitoSpyBean private TicketRepository ticketRepository;

    private SeatedShowFixture fixture;

    @BeforeEach
    void openMarket() {
        fixture = new SeatedShowFixture(authService, companyService, eventManagementService, catalogService,
                systemAdminService, clock);
        fixture.ensureMarketOpen();
    }

    @Test
    void GivenTheSecondTicketFlipFails_WhenAMemberRefundsATwoTicketOrder_ThenNeitherTicketIsRefundedAndTheReceiptIs() {
        Show show = fixture.publishSeatedShow();
        AuthTokenDTO member = fixture.registerAndLogin("flipFailure");
        reservationService.reserveForMember(member.token(), show.eventId(), show.zoneId(),
                InventorySelectionDTO.seated(BOUGHT_SEATS));
        CheckoutResultDTO bought = checkoutService.checkoutMember(
                member.token(), "flip-failure-" + UUID.randomUUID(), CURRENCY, CARD);
        List<TicketStatus> statusesBeforeTheRefund = statusesOf(bought.orderReceiptId());
        failTheSecondTicketSave();

        refundService.requestRefund(member.token(), bought.orderReceiptId(), "Can't attend");

        assertAll(
                () -> assertEquals(statusesBeforeTheRefund, statusesOf(bought.orderReceiptId()),
                        "the failed flips must roll back together, leaving both tickets as they were"),
                () -> assertTrue(statusesBeforeTheRefund.stream().noneMatch(TicketStatus.REFUNDED::equals),
                        "arrangement: the bought tickets are not refunded before the refund"),
                () -> assertTrue(orderReceiptRepository.findByOrderReceiptId(bought.orderReceiptId())
                                .orElseThrow().isFullyRefunded(),
                        "the money is back, so the receipt's refund stands"),
                () -> assertEquals(SeatedShowFixture.SEATS.size() - BOUGHT_SEATS.size(), fixture.availableSeats(show),
                        "seats whose tickets are still valid must not return to stock"));
    }

    // Counts from now, so the checkout's own ticket saves above are not counted.
    private void failTheSecondTicketSave() {
        AtomicInteger saves = new AtomicInteger();
        doAnswer(invocation -> {
            if (saves.incrementAndGet() == 2) {
                throw new DataAccessResourceFailureException("database unreachable");
            }
            return invocation.callRealMethod();
        }).when(ticketRepository).save(any());
    }

    private List<TicketStatus> statusesOf(int orderReceiptId) {
        return ticketRepository.findByOrderReceiptId(orderReceiptId).stream()
                .sorted((a, b) -> Integer.compare(a.getId(), b.getId()))
                .map(Ticket::getStatus)
                .toList();
    }
}
