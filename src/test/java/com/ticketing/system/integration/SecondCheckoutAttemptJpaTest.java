package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.integration.SeatedShowFixture.Show;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.application.port.out.PaymentGateway;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.domain.CartLineItem;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CardDetailsDTO;
import com.ticketing.system.shared.dto.CheckoutResultDTO;

/**
 * UC-15 / UC-16, the 3-phase checkout (#185): an order frozen as CHECKOUT_IN_PROGRESS belongs to the
 * attempt that froze it while that attempt is still running in this process. A second attempt then
 * fails and leaves the order, its holds and its cart alone, or the first attempt would charge for
 * seats it no longer holds. An order frozen by an attempt that is no longer running (the process
 * restarted during its external calls) belongs to nobody: a retry fails as before, and rolls the order
 * back, so its seats return to stock and the buyer can start over.
 *
 * <p>An attempt that is no longer running is stood in for by its Phase-1 effect, the order saved as
 * CHECKOUT_IN_PROGRESS. An attempt still in flight is a real one, held inside Phase 2 by a payment
 * gateway that waits on a latch. Runs on the Jpa adapters over H2.
 */
@SpringBootTest
@ActiveProfiles("test")
class SecondCheckoutAttemptJpaTest {

    private static final String HELD_SEAT = SeatedShowFixture.SEATS.get(0);
    private static final String CURRENCY = "ILS";
    private static final CardDetailsDTO CARD = new CardDetailsDTO("4111111111111234", "123", 12, 2030, "Test Holder");
    private static final String GUEST_EMAIL = "second-attempt-guest@test.com";
    private static final String CHECKOUT_FAILED = "Checkout failed, tickets returned to stock";
    private static final long AWAIT_SECONDS = 20;

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private CatalogService catalogService;
    @Autowired private SystemAdminService systemAdminService;
    @Autowired private ReservationService reservationService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private OrderReceiptRepository orderReceiptRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Clock clock;

    // The test profile's stub gateway, spied on only to hold one charge open in the in-flight case.
    @MockitoSpyBean private PaymentGateway paymentGateway;
    // The Jpa adapter, spied on only to run other attempts at the moment an attempt freezes the order.
    @MockitoSpyBean private ActiveOrderRepository activeOrderRepository;

    private SeatedShowFixture fixture;
    private Show show;

    @BeforeEach
    void publishShow() {
        fixture = new SeatedShowFixture(authService, companyService, eventManagementService, catalogService,
                systemAdminService, clock);
        fixture.ensureMarketOpen();
        show = fixture.publishSeatedShow();
    }

    // ---- frozen by an attempt that is no longer running ----

    @Test
    void GivenMemberOrderFrozenByAnAttemptThatIsNoLongerRunning_WhenCheckoutRetried_ThenItIsRolledBackAndItsSeatsReleased() {
        AuthTokenDTO member = fixture.registerAndLogin("deadAttemptMember");
        reservationService.reserveForMember(member.token(), show.eventId(), show.zoneId(),
                InventorySelectionDTO.seated(List.of(HELD_SEAT)));
        freezeAsADeadAttemptLeftIt(() -> activeOrderRepository.getByUserId(member.userId()));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> checkoutService.checkoutMember(member.token(), newIdempotencyKey(), CURRENCY, CARD),
                "the retry cannot check out an order that is still marked in checkout");

        assertFailedBecauseTheOrderWasInCheckout(failure);
        assertRolledBack(Optional.ofNullable(activeOrderRepository.getByUserId(member.userId())));
    }

    @Test
    void GivenGuestOrderFrozenByAnAttemptThatIsNoLongerRunning_WhenCheckoutRetried_ThenItIsRolledBackAndItsSeatsReleased() {
        String guestSessionId = authService.startGuestSession().sessionId();
        reservationService.reserveForGuest(guestSessionId, show.eventId(), show.zoneId(),
                InventorySelectionDTO.seated(List.of(HELD_SEAT)));
        freezeAsADeadAttemptLeftIt(() -> activeOrderRepository.getBySessionId(guestSessionId).orElseThrow());

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> checkoutService.checkoutGuest(guestSessionId, GUEST_EMAIL, newIdempotencyKey(), CURRENCY, CARD,
                        SeatedShowFixture.MEMBER_AGE),
                "the retry cannot check out an order that is still marked in checkout");

        assertFailedBecauseTheOrderWasInCheckout(failure);
        assertRolledBack(activeOrderRepository.getBySessionId(guestSessionId));
    }

    // ---- frozen by an attempt still in flight ----

    @Test
    void GivenACheckoutStillInFlight_WhenASecondAttemptRuns_ThenTheFirstIsLeftFrozenWithItsHolds() throws Exception {
        AuthTokenDTO member = fixture.registerAndLogin("inFlightMember");
        reservationService.reserveForMember(member.token(), show.eventId(), show.zoneId(),
                InventorySelectionDTO.seated(List.of(HELD_SEAT)));
        CountDownLatch charging = new CountDownLatch(1);
        CountDownLatch releaseCharge = new CountDownLatch(1);
        holdTheFirstChargeOpen(charging, releaseCharge);
        ExecutorService firstAttemptThread = Executors.newSingleThreadExecutor();
        try {
            Future<CheckoutResultDTO> firstAttempt = firstAttemptThread.submit(
                    () -> checkoutService.checkoutMember(member.token(), newIdempotencyKey(), CURRENCY, CARD));
            assertTrue(charging.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                    "arrangement: the first attempt reaches its charge, with the order frozen");

            RuntimeException secondFailure = assertThrows(RuntimeException.class,
                    () -> checkoutService.checkoutMember(member.token(), newIdempotencyKey(), CURRENCY, CARD),
                    "an order in another attempt's checkout cannot be checked out a second time");

            assertFailedBecauseTheOrderWasInCheckout(secondFailure);
            firstAttemptState(member).assertFrozenWithItsSeat();

            releaseCharge.countDown();
            CheckoutResultDTO firstResult = firstAttempt.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue(orderReceiptRepository.findByOrderReceiptId(firstResult.orderReceiptId()).isPresent(),
                    "the first attempt, left alone, completes its purchase");
        } finally {
            releaseCharge.countDown();
            firstAttemptThread.shutdownNow();
        }
    }

    // ---- the moment an attempt freezes the order ----
    // On the Jpa adapter the order lock is a no-op and each save commits on its own, so other attempts
    // see the frozen order the moment its save commits.

    @Test
    void GivenAFirstAttemptWhoseFreezeHasJustCommitted_WhenASecondAttemptRuns_ThenTheFirstIsLeftFrozenWithItsHolds() {
        AuthTokenDTO member = fixture.registerAndLogin("justFrozenMember");
        reservationService.reserveForMember(member.token(), show.eventId(), show.zoneId(),
                InventorySelectionDTO.seated(List.of(HELD_SEAT)));
        ExecutorService otherThreads = Executors.newCachedThreadPool();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        AtomicReference<FirstAttemptState> stateAfterSecond = new AtomicReference<>();
        AtomicInteger freezes = new AtomicInteger();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (isFreeze(invocation) && freezes.incrementAndGet() == 1) {
                afterTheSaveCommits(() -> {
                    secondFailure.set(failureOf(() -> anotherAttempt(otherThreads, member)));
                    stateAfterSecond.set(onAnotherThread(otherThreads, () -> firstAttemptState(member)));
                });
            }
            return null;
        }).when(activeOrderRepository).save(any());
        try {
            Throwable firstFailure = failureOf(
                    () -> checkoutService.checkoutMember(member.token(), newIdempotencyKey(), CURRENCY, CARD));

            assertFailedBecauseTheOrderWasInCheckout(secondFailure.get());
            stateAfterSecond.get().assertFrozenWithItsSeat();
            assertNull(firstFailure, "the first attempt, left alone, completes its purchase");
        } finally {
            otherThreads.shutdownNow();
        }
    }

    // Both attempts pass validation before either freezes the order; the loser's save then fails on the
    // winner's @Version. The loser's cleanup must leave the winner recorded as running, or a later attempt
    // would take the winner's order for one that a dead attempt left behind.
    @Test
    void GivenTwoAttemptsRacingToFreezeOneOrder_WhenTheLosersSaveFails_ThenALaterAttemptLeavesTheWinnerFrozen() {
        AuthTokenDTO member = fixture.registerAndLogin("racingFreezeMember");
        reservationService.reserveForMember(member.token(), show.eventId(), show.zoneId(),
                InventorySelectionDTO.seated(List.of(HELD_SEAT)));
        Thread winner = Thread.currentThread();
        ExecutorService otherThreads = Executors.newCachedThreadPool();
        CountDownLatch loserAtItsFreeze = new CountDownLatch(1);
        CountDownLatch winnerFrozen = new CountDownLatch(1);
        AtomicReference<Throwable> loserFailure = new AtomicReference<>();
        AtomicBoolean winnerPastItsFreeze = new AtomicBoolean(false);
        AtomicReference<Throwable> laterFailure = new AtomicReference<>();
        AtomicReference<FirstAttemptState> stateAfterLater = new AtomicReference<>();
        AtomicInteger freezes = new AtomicInteger();
        doAnswer(invocation -> {
            int freeze = isFreeze(invocation) ? freezes.incrementAndGet() : 0;
            if (freeze == 1) {
                Future<CheckoutResultDTO> loser = otherThreads.submit(() ->
                        checkoutService.checkoutMember(member.token(), newIdempotencyKey(), CURRENCY, CARD));
                awaitOrFail(loserAtItsFreeze, "arrangement: the loser validates the order before it is frozen");
                invocation.callRealMethod();
                afterTheSaveCommits(() -> {
                    winnerFrozen.countDown();
                    loserFailure.set(failureOf(() -> loser.get(AWAIT_SECONDS, TimeUnit.SECONDS)));
                    winnerPastItsFreeze.set(true);
                });
            } else if (freeze == 2) {
                loserAtItsFreeze.countDown();
                awaitOrFail(winnerFrozen, "arrangement: the winner's freeze commits first");
                invocation.callRealMethod();
            } else {
                invocation.callRealMethod();
            }
            return null;
        }).when(activeOrderRepository).save(any());
        // The winner releases the order lock right after its freeze.
        doAnswer(invocation -> {
            if (Thread.currentThread() == winner && winnerPastItsFreeze.getAndSet(false)) {
                laterFailure.set(failureOf(() -> anotherAttempt(otherThreads, member)));
                stateAfterLater.set(onAnotherThread(otherThreads, () -> firstAttemptState(member)));
            }
            return invocation.callRealMethod();
        }).when(activeOrderRepository).unlock(any());
        try {
            Throwable winnerFailure = failureOf(
                    () -> checkoutService.checkoutMember(member.token(), newIdempotencyKey(), CURRENCY, CARD));

            assertNotNull(loserFailure.get(), "arrangement: the loser's freeze must lose to the winner's");
            assertFailedBecauseTheOrderWasInCheckout(laterFailure.get());
            stateAfterLater.get().assertFrozenWithItsSeat();
            assertNull(winnerFailure, "the winner, left alone, completes its purchase");
        } finally {
            winnerFrozen.countDown();
            otherThreads.shutdownNow();
        }
    }

    // ---- assertions and arrangement ----

    // The first attempt's order and its zone's stock, read while that attempt is still running. Each facet
    // is reported on its own, so a partial fix shows what is left.
    private record FirstAttemptState(boolean inCheckout, List<String> seats, int availableSeats) {

        void assertFrozenWithItsSeat() {
            assertAll(
                    () -> assertTrue(inCheckout,
                            "another attempt must not reset the first attempt's CHECKOUT_IN_PROGRESS"),
                    () -> assertEquals(List.of(HELD_SEAT), seats,
                            "another attempt must not clear the first attempt's cart"),
                    () -> assertEquals(SeatedShowFixture.SEATS.size() - 1, availableSeats,
                            "another attempt must not return the first attempt's " + HELD_SEAT + " to stock"));
        }
    }

    private FirstAttemptState firstAttemptState(AuthTokenDTO member) {
        ActiveOrder order = activeOrderRepository.getByUserId(member.userId());
        return new FirstAttemptState(order != null && order.isCheckoutInProgress(),
                order == null ? List.of() : order.getItems().stream().map(CartLineItem::getSeatNumber).toList(),
                fixture.availableSeats(show));
    }

    private CheckoutResultDTO anotherAttempt(ExecutorService otherThreads, AuthTokenDTO member) throws Exception {
        return onAnotherThread(otherThreads,
                () -> checkoutService.checkoutMember(member.token(), newIdempotencyKey(), CURRENCY, CARD));
    }

    // Work run from inside an attempt goes to another thread: on the attempt's own thread it would join the
    // attempt's persistence context, and read the attempt's own copy of the order rather than the database.
    private static <T> T onAnotherThread(ExecutorService otherThreads, Callable<T> work) throws Exception {
        return otherThreads.submit(work).get(AWAIT_SECONDS, TimeUnit.SECONDS);
    }

    // The spy sits inside the adapter's @Transactional proxy, so a save's answer runs before that save's
    // transaction commits. Work that must see the saved order waits for the commit.
    private static void afterTheSaveCommits(Work work) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    work.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while running work after the save committed", e);
                } catch (Exception e) {
                    throw new IllegalStateException("the work run after the save committed failed", e);
                }
            }
        });
    }

    private interface Work {
        void run() throws Exception;
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

    private static void awaitOrFail(CountDownLatch latch, String what) throws InterruptedException {
        if (!latch.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException(what + ", but it never did");
        }
    }

    private static void assertFailedBecauseTheOrderWasInCheckout(Throwable failure) {
        assertNotNull(failure, "the attempt must fail, but it completed");
        assertEquals(CHECKOUT_FAILED, failure.getMessage(), "the checkout failure wrapper");
        assertInstanceOf(IllegalStateException.class, failure.getCause(),
                "the attempt fails on the order already being in checkout, but the cause was " + failure.getCause());
    }

    // Whether the emptied cart row is kept or deleted is not specified; either way nothing is held.
    private void assertRolledBack(Optional<ActiveOrder> order) {
        assertAll(
                () -> assertFalse(order.map(ActiveOrder::isCheckoutInProgress).orElse(false),
                        "the order must no longer be marked in checkout"),
                () -> assertEquals(List.of(), order.map(ActiveOrder::getItems).orElse(List.of()),
                        "the order's cart must be cleared"),
                () -> assertEquals(SeatedShowFixture.SEATS.size(), fixture.availableSeats(show),
                        HELD_SEAT + " must be back in stock"));
    }

    // The Phase-1 effect of an attempt that then died: the order saved as CHECKOUT_IN_PROGRESS.
    private void freezeAsADeadAttemptLeftIt(Supplier<ActiveOrder> order) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ActiveOrder toFreeze = order.get();
            toFreeze.markCheckoutInProgress();
            activeOrderRepository.save(toFreeze);
        });
    }

    // Only the first charge waits, so no other call in the test can be held by it.
    private void holdTheFirstChargeOpen(CountDownLatch charging, CountDownLatch releaseCharge) {
        AtomicBoolean firstCharge = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (firstCharge.getAndSet(false)) {
                charging.countDown();
                if (!releaseCharge.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never released the held charge");
                }
            }
            return invocation.callRealMethod();
        }).when(paymentGateway).charge(any());
    }

    private static String newIdempotencyKey() {
        return "second-attempt-" + UUID.randomUUID();
    }
}
