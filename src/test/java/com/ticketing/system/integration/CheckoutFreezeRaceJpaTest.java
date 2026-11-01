package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.integration.SeatedShowFixture.Show;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.sales.application.port.out.PaymentGateway;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.domain.CartLineItem;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CardDetailsDTO;

/**
 * UC-15 / UC-16, the 3-phase checkout (#187): a checkout whose Phase-1 freeze loses to a cart edit
 * that committed in between changed nothing, so it must leave the cart and its holds as that edit left
 * them. On the Jpa adapter the order lock is a no-op: Phase 1 reads the order detached and saves it
 * frozen, and a reservation from another tab that commits in between makes that save fail on the
 * order's {@code @Version}. The failed attempt must then not release the cart's seats, the one the
 * other tab just reserved included, nor clear the cart.
 *
 * <p>The other tab is a real reservation, run on another thread from inside the attempt's freeze save,
 * before that save reaches the database. Runs on the Jpa adapters over H2.
 */
@SpringBootTest
@ActiveProfiles("test")
class CheckoutFreezeRaceJpaTest {

    private static final String FIRST_SEAT = SeatedShowFixture.SEATS.get(0);
    private static final String SECOND_SEAT = SeatedShowFixture.SEATS.get(1);
    private static final String CURRENCY = "ILS";
    private static final CardDetailsDTO CARD = new CardDetailsDTO("4111111111111234", "123", 12, 2030, "Test Holder");
    private static final String GUEST_EMAIL = "freeze-race-guest@test.com";
    private static final long AWAIT_SECONDS = 20;

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private CatalogService catalogService;
    @Autowired private SystemAdminService systemAdminService;
    @Autowired private ReservationService reservationService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private Clock clock;

    // The same spies as SecondCheckoutAttemptJpaTest, so both classes share one cached context. The
    // gateway is spied on only to show the losing attempt charged nothing.
    @MockitoSpyBean private PaymentGateway paymentGateway;
    // The Jpa adapter, spied on only to run the other tab's reservation at the moment the attempt freezes.
    @MockitoSpyBean private ActiveOrderRepository activeOrderRepository;

    private SeatedShowFixture fixture;
    private Show show;
    private ExecutorService otherTab;

    @BeforeEach
    void publishShow() {
        fixture = new SeatedShowFixture(authService, companyService, eventManagementService, catalogService,
                systemAdminService, clock);
        fixture.ensureMarketOpen();
        show = fixture.publishSeatedShow();
        otherTab = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void stopOtherTab() {
        otherTab.shutdownNow();
    }

    @Test
    void GivenMemberReservesFromAnotherTabBeforeTheFreezeSaves_WhenCheckoutFreezeLoses_ThenTheCartAndBothHoldsAreKept() {
        AuthTokenDTO member = fixture.registerAndLogin("freezeRaceMember");
        reservationService.reserveForMember(member.token(), show.eventId(), show.zoneId(), seat(FIRST_SEAT));
        AtomicReference<Throwable> otherTabFailure = reserveFromAnotherTabWhenTheAttemptFreezes(
                () -> reservationService.reserveForMember(member.token(), show.eventId(), show.zoneId(),
                        seat(SECOND_SEAT)));

        assertThrows(RuntimeException.class,
                () -> checkoutService.checkoutMember(member.token(), newIdempotencyKey(), CURRENCY, CARD),
                "arrangement: an attempt whose freeze lost to another tab must fail, as it priced a stale cart");

        assertNull(otherTabFailure.get(), "arrangement: the other tab's reservation must commit");
        verify(paymentGateway, never()).charge(any());
        assertCartAndHoldsKept(activeOrderRepository.getByUserId(member.userId()));
    }

    @Test
    void GivenGuestReservesFromAnotherTabBeforeTheFreezeSaves_WhenCheckoutFreezeLoses_ThenTheCartAndBothHoldsAreKept() {
        String guestSessionId = authService.startGuestSession().sessionId();
        reservationService.reserveForGuest(guestSessionId, show.eventId(), show.zoneId(), seat(FIRST_SEAT));
        AtomicReference<Throwable> otherTabFailure = reserveFromAnotherTabWhenTheAttemptFreezes(
                () -> reservationService.reserveForGuest(guestSessionId, show.eventId(), show.zoneId(),
                        seat(SECOND_SEAT)));

        assertThrows(RuntimeException.class,
                () -> checkoutService.checkoutGuest(guestSessionId, GUEST_EMAIL, newIdempotencyKey(), CURRENCY, CARD,
                        SeatedShowFixture.MEMBER_AGE),
                "arrangement: an attempt whose freeze lost to another tab must fail, as it priced a stale cart");

        assertNull(otherTabFailure.get(), "arrangement: the other tab's reservation must commit");
        verify(paymentGateway, never()).charge(any());
        assertCartAndHoldsKept(activeOrderRepository.getBySessionId(guestSessionId).orElse(null));
    }

    // ---- assertions and arrangement ----

    // Each facet is reported on its own, so a partial fix shows what is left.
    private void assertCartAndHoldsKept(ActiveOrder order) {
        assertNotNull(order, "the cart must survive the failed attempt");
        assertAll(
                () -> assertFalse(order.isCheckoutInProgress(),
                        "the failed attempt never froze the cart, so it must not be left in checkout"),
                () -> assertEquals(List.of(FIRST_SEAT, SECOND_SEAT), sortedSeats(order),
                        "the failed attempt must not clear the cart, nor drop the line the other tab added"),
                () -> assertEquals(SeatedShowFixture.SEATS.size() - 2, fixture.availableSeats(show),
                        "the failed attempt must not return " + FIRST_SEAT + " or " + SECOND_SEAT + " to stock"));
    }

    // The first freeze save runs the other tab's reservation to its commit, then saves: the reservation
    // bumped the order's @Version, so the attempt's save of the copy it read in Phase 1 loses. The
    // reservation's outcome is returned rather than thrown, so a broken arrangement cannot pass for the
    // attempt's own failure.
    private AtomicReference<Throwable> reserveFromAnotherTabWhenTheAttemptFreezes(Callable<?> reservation) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean raced = new AtomicBoolean(false);
        doAnswer(invocation -> {
            if (isFreeze(invocation) && raced.compareAndSet(false, true)) {
                failure.set(failureOf(() -> otherTab.submit(reservation).get(AWAIT_SECONDS, TimeUnit.SECONDS)));
            }
            return invocation.callRealMethod();
        }).when(activeOrderRepository).save(any());
        return failure;
    }

    private static boolean isFreeze(InvocationOnMock save) {
        return save.<ActiveOrder>getArgument(0).isCheckoutInProgress();
    }

    /** What the call threw, unwrapped from a Future's ExecutionException; {@code null} when it returned. */
    private static Throwable failureOf(Callable<?> call) {
        try {
            call.call();
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            return e;
        }
    }

    private static List<String> sortedSeats(ActiveOrder order) {
        return order.getItems().stream().map(CartLineItem::getSeatNumber).sorted().toList();
    }

    private static InventorySelectionDTO seat(String seat) {
        return InventorySelectionDTO.seated(List.of(seat));
    }

    private static String newIdempotencyKey() {
        return "freeze-race-" + UUID.randomUUID();
    }
}
