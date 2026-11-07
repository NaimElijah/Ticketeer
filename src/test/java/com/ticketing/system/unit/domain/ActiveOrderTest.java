package com.ticketing.system.unit.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.support.BaseDomainTest;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.shared.exception.CheckoutInProgressException;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.sales.domain.CartLineItem;

// Unit tests for the ActiveOrder aggregate.
class ActiveOrderTest extends BaseDomainTest {

    // The hold is judged against this instant, as UTC wall time from the injected clock (ADR-008).
    private static final LocalDateTime NOW = LocalDateTime.of(2027, 7, 15, 17, 0);
    // The hold length is process-wide state that any Spring context sets from configuration
    // (ReservationHoldConfig), so every time below is read from it rather than assuming ten minutes.
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);

    private static Duration hold() {
        return CartLineItem.getExpirationLimit();
    }

    // Added when one minute of the hold is left at NOW.
    private static LocalDateTime nearExpiry() {
        return NOW.minus(hold()).plus(ONE_MINUTE);
    }

    // Added so long ago that the hold ran out a minute before NOW.
    private static LocalDateTime pastTheHold() {
        return NOW.minus(hold()).minus(ONE_MINUTE);
    }

    private final int USER_ID = 1;
    private final int EVENT_ID = 10;
    private final int ZONE_ID = 5;

    @Test
    @Disabled("V1: addReservation appends CartLineItem (UC-5)")
    void GivenEmptyOrder_WhenAddReservation_ThenLineAdded() {
    }

    // UC-10: an empty cart, or one whose hold has run out, cannot be checked out; the buyer reads the refusal
    // as an expired order (CheckoutPresenter), so it is a state transition, not an internal error (#187).

    @Test
    void GivenEmptyOrder_WhenValidateCanCheckout_ThenRefusedAsAnInvalidStateTransition() {
        ActiveOrder order = track(new ActiveOrder(USER_ID, NOW));

        assertThrows(InvalidStateTransitionException.class, () -> order.validateCanCheckout(NOW));
    }

    @Test
    void GivenOrderWhoseHoldHasRunOut_WhenValidateCanCheckout_ThenRefusedAsAnInvalidStateTransition() {
        ActiveOrder order = track(new ActiveOrder(USER_ID, NOW));
        order.addStandingReservation(EVENT_ID, ZONE_ID, 1, 50.0, pastTheHold());

        assertThrows(InvalidStateTransitionException.class, () -> order.validateCanCheckout(NOW));
    }

    // UC-10: checking out an order already frozen in checkout (a member paying from a second device, or an
    // interrupted attempt) is an ordinary refusal the buyer reads, not an internal error (NaimElijah/Ticketeer#240).
    @Test
    void GivenOrderAlreadyInCheckout_WhenValidateCanCheckout_ThenRefusedAsCheckoutInProgress() {
        ActiveOrder order = track(new ActiveOrder(USER_ID, NOW));
        order.addStandingReservation(EVENT_ID, ZONE_ID, 1, 50.0, NOW);
        order.markCheckoutInProgress();

        CheckoutInProgressException refusal =
                assertThrows(CheckoutInProgressException.class, () -> order.validateCanCheckout(NOW));
        assertEquals("This order is already being paid for", refusal.getMessage());
    }

    @Test
    @Disabled("V1: ReturnToStock empties order (UC-2 / UC-14)")
    void GivenOrderWithItems_WhenReturnToStock_ThenOrderEmpty() {
    }

    @Test
    void GivenManyThreadsRemoveStandingSpotsFromSameActiveOrder_WhenRemoveStandingSpots_ThenDoNotRemoveMoreThanExists()
            throws InterruptedException {

        int initialTickets = 5;
        int numberOfThreads = 20;
        int quantityPerRemove = 1;

        ActiveOrder realActiveOrder = track(new ActiveOrder(USER_ID, NOW));

        realActiveOrder.addStandingReservation(EVENT_ID, ZONE_ID, initialTickets, 100.0, NOW);

        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);

        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        // Only "not enough to remove" is the expected refusal; anything else is a race the order lost.
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        try {
            for (int i = 0; i < numberOfThreads; i = i + 1) {
                executorService.submit(() -> {
                    try {
                        readyLatch.countDown();
                        startLatch.await();

                        realActiveOrder.removeStandingSpots(EVENT_ID, ZONE_ID, quantityPerRemove);

                        successCount.incrementAndGet();

                    } catch (IllegalArgumentException e) {
                        failureCount.incrementAndGet();

                    } catch (Throwable e) {
                        unexpected.add(e);

                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            assertTrue(readyLatch.await(5, TimeUnit.SECONDS), "every remover must be ready within 5 s");
            startLatch.countDown();

            boolean finished = doneLatch.await(5, TimeUnit.SECONDS);

            assertEquals(true, finished);
            assertEquals(List.of(), List.copyOf(unexpected), "no remover may fail other than by the refusal");
            assertEquals(initialTickets, successCount.get());
            assertEquals(numberOfThreads - initialTickets, failureCount.get());
            assertEquals(0, realActiveOrder.countTickets(EVENT_ID, ZONE_ID, NOW));

        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void GivenStandingReservation_WhenAddStandingReservation_ThenCreatesQuantityLineItemsWithNullSeatNumber() {
        ActiveOrder order = track(new ActiveOrder(USER_ID, NOW));

        order.addStandingReservation(EVENT_ID, ZONE_ID, 3, 100.0, NOW);

        assertEquals(3, order.countTickets(EVENT_ID, ZONE_ID, NOW));
        assertEquals(3, order.getItems().size());

        for (CartLineItem item : order.getItems()) {
            assertEquals(EVENT_ID, item.geteventId());
            assertEquals(ZONE_ID, item.getzoneId());
            assertNull(item.getSeatNumber());
            assertTrue(item.isStandingTicket());
        }
    }

    @Test
    void GivenSeatSelection_WhenAddSeatedReservation_ThenCreatesOneLinePerSeat() {
        ActiveOrder order = track(new ActiveOrder(USER_ID, NOW));

        order.addSeatedReservation(
                EVENT_ID,
                ZONE_ID,
                List.of("A1", "A2", "A3"),
                120.0,
                NOW);

        assertEquals(3, order.getItems().size());
        assertEquals(List.of("A1", "A2", "A3"),
                order.getItems().stream().map(CartLineItem::getSeatNumber).toList());
    }

    @Test
    void GivenDuplicateSeats_WhenAddSeatedReservation_ThenThrowsException() {
        ActiveOrder order = track(new ActiveOrder(USER_ID, NOW));

        assertThrows(IllegalArgumentException.class, () -> order.addSeatedReservation(
                EVENT_ID,
                ZONE_ID,
                List.of("A1", "A1"),
                120.0,
                NOW));

        assertTrue(order.getItems().isEmpty());
    }

    @Test
    void GivenSeatedReservation_WhenRemoveSeats_ThenOnlySelectedSeatsRemoved() {
        ActiveOrder order = track(new ActiveOrder(USER_ID, NOW));

        order.addSeatedReservation(
                EVENT_ID,
                ZONE_ID,
                List.of("A1", "A2", "A3"),
                120.0,
                NOW);

        order.removeSeats(EVENT_ID, ZONE_ID, List.of("A2"));

        assertEquals(2, order.getItems().size());
        assertEquals(List.of("A1", "A3"),
                order.getItems().stream().map(CartLineItem::getSeatNumber).toList());
    }

    @Test
    void GivenMissingSeat_WhenRemoveSeats_ThenThrowsAndOrderUnchanged() {
        ActiveOrder order = track(new ActiveOrder(USER_ID, NOW));

        order.addSeatedReservation(
                EVENT_ID,
                ZONE_ID,
                List.of("A1", "A2"),
                120.0,
                NOW);

        assertThrows(IllegalArgumentException.class, () -> order.removeSeats(EVENT_ID, ZONE_ID, List.of("A3")));

        assertEquals(2, order.getItems().size());
        assertEquals(List.of("A1", "A2"),
                order.getItems().stream().map(CartLineItem::getSeatNumber).toList());
    }

    @Test
    void GivenMixedStandingAndSeatedItems_WhenReturnToStock_ThenReturnsAllItemsAndClearsOrder() {
        ActiveOrder order = track(new ActiveOrder(USER_ID, NOW));

        order.addStandingReservation(EVENT_ID, ZONE_ID, 2, 50.0, NOW);
        order.addSeatedReservation(EVENT_ID, ZONE_ID + 1, List.of("A1", "A2"), 120.0, NOW);

        List<CartLineItem> returned = order.ReturnToStock();

        assertEquals(List.of(
                        EVENT_ID + "/" + ZONE_ID + "/standing",
                        EVENT_ID + "/" + ZONE_ID + "/standing",
                        EVENT_ID + "/" + (ZONE_ID + 1) + "/A1",
                        EVENT_ID + "/" + (ZONE_ID + 1) + "/A2"),
                returned.stream().map(ActiveOrderTest::eventZoneAndPlace).toList(),
                "exactly the lines the order held go back to stock");
        assertTrue(order.isEmpty());
    }

    private static String eventZoneAndPlace(CartLineItem line) {
        return line.geteventId() + "/" + line.getzoneId() + "/"
                + (line.getSeatNumber() == null ? "standing" : line.getSeatNumber());
    }

    @Test
    void GivenSeatedCartLine_WhenConvertToDTO_ThenDTOContainsSeatNumber() {
        CartLineItem item = new CartLineItem(
                EVENT_ID,
                ZONE_ID,
                "A7",
                120.0,
                NOW);

        ActiveOrderDTO.CartLineDTO dto = item.toDTO();

        assertEquals(EVENT_ID, dto.eventId());
        assertEquals(ZONE_ID, dto.zoneId());
        assertEquals("A7", dto.seatNumber());
        assertEquals(120.0, dto.pricePerTicket());
    }

    @Test
    void GivenDuplicateSeatNumbers_WhenRemoveSeats_ThenThrowsAndCartUnchanged() {
        ActiveOrder order = track(new ActiveOrder(1, NOW));
        order.addSeatedReservation(10, 2, List.of("A1", "A2"), 100, NOW);

        assertThrows(IllegalArgumentException.class,
                () -> order.removeSeats(10, 2, List.of("A1", "A1")));

        assertEquals(List.of("A1", "A2"),
                order.getItems().stream().map(CartLineItem::getSeatNumber).toList());
    }

    @Test
    void GivenItemsNearExpiry_WhenRenewReservationTimers_ThenAllTimersResetToFullWindow() {
        ActiveOrder order = track(new ActiveOrder(USER_ID, NOW));

        order.addStandingReservation(EVENT_ID, ZONE_ID, 2, 50.0, nearExpiry());
        order.addSeatedReservation(EVENT_ID, ZONE_ID + 1, List.of("A1"), 120.0, nearExpiry());

        assertEquals(ONE_MINUTE, order.getRemainingTime(NOW), "precondition: one minute of the hold is left");

        order.renewReservationTimers(NOW);

        assertEquals(hold(), order.getRemainingTime(NOW), "the whole hold starts again at NOW");
        for (CartLineItem item : order.getItems()) {
            assertEquals(NOW, item.getAddedAt());
            assertEquals(hold(), item.getRemainingTime(NOW));
            assertFalse(item.isExpired(NOW));
        }
    }

    @Test
    void GivenCheckoutInProgress_WhenRenewReservationTimers_ThenThrows() {
        ActiveOrder order = track(new ActiveOrder(USER_ID, NOW));
        order.addStandingReservation(EVENT_ID, ZONE_ID, 1, 50.0, NOW);
        order.markCheckoutInProgress();

        assertThrows(IllegalStateException.class,
                () -> order.renewReservationTimers(NOW));
    }

    @Test
    void GivenLineItem_WhenRenew_ThenAddedAtUpdatedAndTimerFresh() {
        CartLineItem item = new CartLineItem(EVENT_ID, ZONE_ID, "A7", 120.0, nearExpiry());

        assertEquals(ONE_MINUTE, item.getRemainingTime(NOW), "precondition: one minute of the hold is left");

        item.renew(NOW);

        assertEquals(NOW, item.getAddedAt());
        assertEquals(hold(), item.getRemainingTime(NOW));
    }

    // R1: a buyer must be able to manually remove an EXPIRED standing line (previously the !isExpired
    // filter made expired lines invisible to removal, so they could only be cleared by the sweeper).
    @Test
    void GivenExpiredStandingLine_WhenRemoveStandingSpots_ThenLineRemoved() {
        ActiveOrder order = track(new ActiveOrder(USER_ID, NOW));
        order.addStandingReservation(EVENT_ID, ZONE_ID, 2, 50.0, pastTheHold());

        // Precondition: the lines are genuinely expired.
        assertTrue(order.getItems().get(0).isExpired(NOW));

        order.removeStandingSpots(EVENT_ID, ZONE_ID, 2);

        assertEquals(0, order.countTickets(EVENT_ID, ZONE_ID, NOW));
        assertTrue(order.isEmpty());
    }

    // R1: same for an expired SEATED line.
    @Test
    void GivenExpiredSeatedLine_WhenRemoveSeats_ThenSeatRemoved() {
        ActiveOrder order = track(new ActiveOrder(USER_ID, NOW));
        order.addSeatedReservation(EVENT_ID, ZONE_ID, List.of("A1", "A2"), 120.0, pastTheHold());

        assertTrue(order.getItems().get(0).isExpired(NOW));

        order.removeSeats(EVENT_ID, ZONE_ID, List.of("A1"));

        assertEquals(List.of("A2"),
                order.getItems().stream().map(CartLineItem::getSeatNumber).toList());
    }

    // ---- Defect 10: attachToUser refuses an id no user can have (#187) ----
    // A user id is positive (checkInvariants), so a lower one is refused as an invalid argument, and the cart
    // stays a guest cart.

    private static final String GUEST_SESSION_ID = "guest-session-1";

    @Test
    void GivenGuestCart_WhenAttachToUserZero_ThenRefusedAndTheCartStaysAGuestCart() {
        ActiveOrder cart = track(ActiveOrder.forGuest(GUEST_SESSION_ID, NOW));

        assertAttachRefused(cart, 0);
    }

    @Test
    void GivenGuestCart_WhenAttachToANegativeUserId_ThenRefusedAndTheCartStaysAGuestCart() {
        ActiveOrder cart = track(ActiveOrder.forGuest(GUEST_SESSION_ID, NOW));

        assertAttachRefused(cart, -1);
    }

    // Guard: the guest-to-member promotion still claims the cart for a real user.
    @Test
    void GivenGuestCart_WhenAttachToAPositiveUserId_ThenTheCartBelongsToThatUser() {
        ActiveOrder cart = track(ActiveOrder.forGuest(GUEST_SESSION_ID, NOW));

        cart.attachToUser(USER_ID);

        assertTrue(cart.isMember(), "the promoted cart must be a member cart");
        assertEquals(USER_ID, cart.getUserId());
    }

    private static void assertAttachRefused(ActiveOrder cart, int userId) {
        RuntimeException refused = assertThrows(RuntimeException.class, () -> cart.attachToUser(userId),
                "no user has id " + userId + ", so a cart must not be attached to it");

        assertInstanceOf(IllegalArgumentException.class, refused,
                "attaching to user id " + userId + " must be refused as an invalid argument");
        assertTrue(cart.isGuest(), "a refused attach must leave the cart a guest cart");
    }
}
