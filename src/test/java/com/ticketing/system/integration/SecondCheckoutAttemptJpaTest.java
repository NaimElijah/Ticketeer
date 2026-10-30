package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.integration.SeatedShowFixture.Show;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.domain.CartLineItem;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CardDetailsDTO;

/**
 * UC-15 / UC-16, the 3-phase checkout (#185): a checkout attempt that finds the order already frozen
 * by another attempt must fail without undoing that attempt. Resetting the status, returning the
 * seats to stock or clearing the cart belongs only to the attempt that froze the order; done by a
 * second attempt, it lets the first one charge for seats it no longer holds.
 *
 * <p>The other attempt is stood in for by its Phase-1 effect, the order saved as CHECKOUT_IN_PROGRESS,
 * so the test needs no threads. Runs on the Jpa adapters over H2.
 */
@SpringBootTest
@ActiveProfiles("test")
class SecondCheckoutAttemptJpaTest {

    private static final String HELD_SEAT = SeatedShowFixture.SEATS.get(0);
    private static final String CURRENCY = "ILS";
    private static final CardDetailsDTO CARD = new CardDetailsDTO("4111111111111234", "123", 12, 2030, "Test Holder");
    private static final String GUEST_EMAIL = "second-attempt-guest@test.com";

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private CatalogService catalogService;
    @Autowired private SystemAdminService systemAdminService;
    @Autowired private ReservationService reservationService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private ActiveOrderRepository activeOrderRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Clock clock;

    private SeatedShowFixture fixture;
    private Show show;

    @BeforeEach
    void publishShow() {
        fixture = new SeatedShowFixture(authService, companyService, eventManagementService, catalogService,
                systemAdminService, clock);
        fixture.ensureMarketOpen();
        show = fixture.publishSeatedShow();
    }

    @Test
    void GivenMemberOrderAlreadyInCheckout_WhenSecondCheckoutAttempt_ThenItFailsAndLeavesTheOrderFrozenWithItsHolds() {
        AuthTokenDTO member = fixture.registerAndLogin("secondAttemptMember");
        reservationService.reserveForMember(member.token(), show.eventId(), show.zoneId(),
                InventorySelectionDTO.seated(List.of(HELD_SEAT)));
        freezeAsTheFirstAttemptWould(() -> activeOrderRepository.getByUserId(member.userId()));

        assertThrows(RuntimeException.class,
                () -> checkoutService.checkoutMember(member.token(), newIdempotencyKey(), CURRENCY, CARD),
                "an order already in checkout cannot be checked out a second time");

        assertFrozenWithItsSeat(activeOrderRepository.getByUserId(member.userId()));
    }

    @Test
    void GivenGuestOrderAlreadyInCheckout_WhenSecondCheckoutAttempt_ThenItFailsAndLeavesTheOrderFrozenWithItsHolds() {
        String guestSessionId = authService.startGuestSession().sessionId();
        reservationService.reserveForGuest(guestSessionId, show.eventId(), show.zoneId(),
                InventorySelectionDTO.seated(List.of(HELD_SEAT)));
        freezeAsTheFirstAttemptWould(() -> activeOrderRepository.getBySessionId(guestSessionId).orElseThrow());

        assertThrows(RuntimeException.class,
                () -> checkoutService.checkoutGuest(guestSessionId, GUEST_EMAIL, newIdempotencyKey(), CURRENCY, CARD,
                        SeatedShowFixture.MEMBER_AGE),
                "an order already in checkout cannot be checked out a second time");

        assertFrozenWithItsSeat(activeOrderRepository.getBySessionId(guestSessionId).orElseThrow());
    }

    // Each facet of the first attempt's state is reported on its own, so a partial fix shows what is left.
    private void assertFrozenWithItsSeat(ActiveOrder order) {
        assertAll(
                () -> assertTrue(order.isCheckoutInProgress(),
                        "the second attempt must not reset the first attempt's CHECKOUT_IN_PROGRESS"),
                () -> assertEquals(List.of(HELD_SEAT),
                        order.getItems().stream().map(CartLineItem::getSeatNumber).toList(),
                        "the second attempt must not clear the first attempt's cart"),
                () -> assertEquals(SeatedShowFixture.SEATS.size() - 1, fixture.availableSeats(show),
                        "the second attempt must not return the first attempt's " + HELD_SEAT + " to stock"));
    }

    // The first attempt's Phase 1: the order saved as CHECKOUT_IN_PROGRESS.
    private void freezeAsTheFirstAttemptWould(Supplier<ActiveOrder> order) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ActiveOrder toFreeze = order.get();
            toFreeze.markCheckoutInProgress();
            activeOrderRepository.save(toFreeze);
        });
    }

    private static String newIdempotencyKey() {
        return "second-attempt-" + UUID.randomUUID();
    }
}
