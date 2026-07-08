package com.ticketing.system.unit.domain;

import java.time.LocalDateTime;
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

import com.ticketing.system.Core.Application.dto.ActiveOrderDTO;
import com.ticketing.system.sales.domain.CartLineItem;

// Unit tests for the ActiveOrder aggregate.
class ActiveOrderTest extends BaseDomainTest {

    private final int USER_ID = 1;
    private final int EVENT_ID = 10;
    private final int ZONE_ID = 5;

    @Test
    @Disabled("V1: addReservation appends CartLineItem (UC-5)")
    void givenEmptyOrder_whenAddReservation_thenLineAdded() {
    }

    @Test
    @Disabled("V1: validateCanCheckout rejects empty (UC-10)")
    void givenEmptyOrder_whenValidateCanCheckout_thenThrows() {
    }

    @Test
    @Disabled("V1: validateCanCheckout rejects expired (UC-10)")
    void givenExpiredItem_whenValidateCanCheckout_thenThrows() {
    }

    @Test
    @Disabled("V1: ReturnToStock empties order (UC-2 / UC-14)")
    void givenOrderWithItems_whenReturnToStock_thenOrderEmpty() {
    }

    @Test
    void GivenManyThreadsRemoveStandingSpotsFromSameActiveOrder_WhenRemoveStandingSpots_ThenDoNotRemoveMoreThanExists()
            throws InterruptedException {

        int initialTickets = 5;
        int numberOfThreads = 20;
        int quantityPerRemove = 1;

        ActiveOrder realActiveOrder = track(new ActiveOrder(USER_ID));

        realActiveOrder.addStandingReservation(EVENT_ID, ZONE_ID, initialTickets, 100.0, LocalDateTime.now());

        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);

        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        try {
            for (int i = 0; i < numberOfThreads; i = i + 1) {
                executorService.submit(() -> {
                    try {
                        readyLatch.countDown();
                        startLatch.await();

                        realActiveOrder.removeStandingSpots(EVENT_ID, ZONE_ID, quantityPerRemove);

                        successCount.incrementAndGet();

                    } catch (Exception e) {
                        failureCount.incrementAndGet();

                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            readyLatch.await();
            startLatch.countDown();

            boolean finished = doneLatch.await(5, TimeUnit.SECONDS);

            assertEquals(true, finished);
            assertEquals(initialTickets, successCount.get());
            assertEquals(numberOfThreads - initialTickets, failureCount.get());
            assertEquals(0, realActiveOrder.countTickets(EVENT_ID, ZONE_ID));

        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void GivenStandingReservation_WhenAddStandingReservation_ThenCreatesQuantityLineItemsWithNullSeatNumber() {
        ActiveOrder order = track(new ActiveOrder(USER_ID));

        order.addStandingReservation(EVENT_ID, ZONE_ID, 3, 100.0, LocalDateTime.now());

        assertEquals(3, order.countTickets(EVENT_ID, ZONE_ID));
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
        ActiveOrder order = track(new ActiveOrder(USER_ID));

        order.addSeatedReservation(
                EVENT_ID,
                ZONE_ID,
                List.of("A1", "A2", "A3"),
                120.0,
                LocalDateTime.now());

        assertEquals(3, order.getItems().size());
        assertEquals(List.of("A1", "A2", "A3"),
                order.getItems().stream().map(CartLineItem::getSeatNumber).toList());
    }

    @Test
    void GivenDuplicateSeats_WhenAddSeatedReservation_ThenThrowsException() {
        ActiveOrder order = track(new ActiveOrder(USER_ID));

        assertThrows(IllegalArgumentException.class, () -> order.addSeatedReservation(
                EVENT_ID,
                ZONE_ID,
                List.of("A1", "A1"),
                120.0,
                LocalDateTime.now()));

        assertTrue(order.getItems().isEmpty());
    }

    @Test
    void GivenSeatedReservation_WhenRemoveSeats_ThenOnlySelectedSeatsRemoved() {
        ActiveOrder order = track(new ActiveOrder(USER_ID));

        order.addSeatedReservation(
                EVENT_ID,
                ZONE_ID,
                List.of("A1", "A2", "A3"),
                120.0,
                LocalDateTime.now());

        order.removeSeats(EVENT_ID, ZONE_ID, List.of("A2"));

        assertEquals(2, order.getItems().size());
        assertEquals(List.of("A1", "A3"),
                order.getItems().stream().map(CartLineItem::getSeatNumber).toList());
    }

    @Test
    void GivenMissingSeat_WhenRemoveSeats_ThenThrowsAndOrderUnchanged() {
        ActiveOrder order = track(new ActiveOrder(USER_ID));

        order.addSeatedReservation(
                EVENT_ID,
                ZONE_ID,
                List.of("A1", "A2"),
                120.0,
                LocalDateTime.now());

        assertThrows(IllegalArgumentException.class, () -> order.removeSeats(EVENT_ID, ZONE_ID, List.of("A3")));

        assertEquals(2, order.getItems().size());
        assertEquals(List.of("A1", "A2"),
                order.getItems().stream().map(CartLineItem::getSeatNumber).toList());
    }

    @Test
    void GivenMixedStandingAndSeatedItems_WhenReturnToStock_ThenReturnsAllItemsAndClearsOrder() {
        ActiveOrder order = track(new ActiveOrder(USER_ID));

        order.addStandingReservation(EVENT_ID, ZONE_ID, 2, 50.0, LocalDateTime.now());
        order.addSeatedReservation(EVENT_ID, ZONE_ID + 1, List.of("A1", "A2"), 120.0, LocalDateTime.now());

        List<CartLineItem> returned = order.ReturnToStock();

        assertEquals(4, returned.size());
        assertTrue(order.isEmpty());
    }

    @Test
    void GivenSeatedCartLine_WhenConvertToDTO_ThenDTOContainsSeatNumber() {
        CartLineItem item = new CartLineItem(
                EVENT_ID,
                ZONE_ID,
                "A7",
                120.0,
                LocalDateTime.now());

        ActiveOrderDTO.CartLineDTO dto = item.toDTO();

        assertEquals(EVENT_ID, dto.eventId());
        assertEquals(ZONE_ID, dto.zoneId());
        assertEquals("A7", dto.seatNumber());
        assertEquals(120.0, dto.pricePerTicket());
    }

    @Test
    void GivenDuplicateSeatNumbers_WhenRemoveSeats_ThenThrowsAndCartUnchanged() {
        ActiveOrder order = new ActiveOrder(1);
        order.addSeatedReservation(10, 2, List.of("A1", "A2"), 100, LocalDateTime.now());

        assertThrows(IllegalArgumentException.class,
                () -> order.removeSeats(10, 2, List.of("A1", "A1")));

        assertEquals(List.of("A1", "A2"),
                order.getItems().stream().map(CartLineItem::getSeatNumber).toList());
    }

    @Test
    void GivenItemsNearExpiry_WhenRenewReservationTimers_ThenAllTimersResetToFullWindow() {
        ActiveOrder order = track(new ActiveOrder(USER_ID));

        // Added 9 minutes ago -> ~1 minute left on the 10-minute window.
        LocalDateTime nineMinutesAgo = LocalDateTime.now().minusMinutes(9);
        order.addStandingReservation(EVENT_ID, ZONE_ID, 2, 50.0, nineMinutesAgo);
        order.addSeatedReservation(EVENT_ID, ZONE_ID + 1, List.of("A1"), 120.0, nineMinutesAgo);

        assertTrue(order.getRemainingTime().getSeconds() < 120,
                "precondition: cart should be near expiry before renewal");

        order.renewReservationTimers(LocalDateTime.now());

        // Every line item now has a fresh ~10-minute window.
        assertTrue(order.getRemainingTime().getSeconds() > 590,
                "all timers should be reset close to the full 10-minute window");
        for (CartLineItem item : order.getItems()) {
            assertTrue(item.getRemainingTime().getSeconds() > 590);
            assertFalse(item.isExpired());
        }
    }

    @Test
    void GivenCheckoutInProgress_WhenRenewReservationTimers_ThenThrows() {
        ActiveOrder order = track(new ActiveOrder(USER_ID));
        order.addStandingReservation(EVENT_ID, ZONE_ID, 1, 50.0, LocalDateTime.now());
        order.markCheckoutInProgress();

        assertThrows(IllegalStateException.class,
                () -> order.renewReservationTimers(LocalDateTime.now()));
    }

    @Test
    void GivenLineItem_WhenRenew_ThenAddedAtUpdatedAndTimerFresh() {
        LocalDateTime nineMinutesAgo = LocalDateTime.now().minusMinutes(9);
        CartLineItem item = new CartLineItem(EVENT_ID, ZONE_ID, "A7", 120.0, nineMinutesAgo);

        assertTrue(item.getRemainingTime().getSeconds() < 120);

        LocalDateTime now = LocalDateTime.now();
        item.renew(now);

        assertEquals(now, item.getAddedAt());
        assertTrue(item.getRemainingTime().getSeconds() > 590);
    }

    // R1: a buyer must be able to manually remove an EXPIRED standing line (previously the !isExpired
    // filter made expired lines invisible to removal, so they could only be cleared by the sweeper).
    @Test
    void GivenExpiredStandingLine_WhenRemoveStandingSpots_ThenLineRemoved() {
        ActiveOrder order = track(new ActiveOrder(USER_ID));
        order.addStandingReservation(EVENT_ID, ZONE_ID, 2, 50.0, LocalDateTime.now().minusMinutes(30));

        // Precondition: the lines are genuinely expired.
        assertTrue(order.getItems().get(0).isExpired());

        order.removeStandingSpots(EVENT_ID, ZONE_ID, 2);

        assertEquals(0, order.countTickets(EVENT_ID, ZONE_ID));
        assertTrue(order.isEmpty());
    }

    // R1: same for an expired SEATED line.
    @Test
    void GivenExpiredSeatedLine_WhenRemoveSeats_ThenSeatRemoved() {
        ActiveOrder order = track(new ActiveOrder(USER_ID));
        order.addSeatedReservation(EVENT_ID, ZONE_ID, List.of("A1", "A2"), 120.0,
                LocalDateTime.now().minusMinutes(30));

        assertTrue(order.getItems().get(0).isExpired());

        order.removeSeats(EVENT_ID, ZONE_ID, List.of("A1"));

        assertEquals(List.of("A2"),
                order.getItems().stream().map(CartLineItem::getSeatNumber).toList());
    }

}
