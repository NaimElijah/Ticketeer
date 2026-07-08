package com.ticketing.system.unit.domain;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.domain.InventorySelection;
import com.ticketing.system.catalog.domain.InventoryZone;
import com.ticketing.system.catalog.domain.Seat;
import com.ticketing.system.catalog.domain.SeatStatus;
import com.ticketing.system.catalog.domain.SeatedZone;
import com.ticketing.system.catalog.domain.StandingZone;
import com.ticketing.system.support.BaseDomainTest;

import static org.junit.jupiter.api.Assertions.*;

import com.ticketing.system.catalog.domain.ZoneType;

public class InventoryZoneTest extends BaseDomainTest {

    private InventoryZone zone;

    private final int ZONE_ID = 5;

    @BeforeEach
    public void setUp() {
        zone = track(new StandingZone(ZONE_ID, "VIP", 10, 100));
    }

    @Test
    void GivenManyThreadsReserveSameInventoryZone_WhenReserveStanding_ThenDoNotOverReserve()
            throws InterruptedException {

        int capacity = 5;
        int numberOfThreads = 20;
        int quantityPerRequest = 1;

        InventoryZone realZone = track(new StandingZone(ZONE_ID, "VIP", capacity, 100.0));

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

                        realZone.reserve(InventorySelection.standing(quantityPerRequest));

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
            assertEquals(capacity, successCount.get());
            assertEquals(numberOfThreads - capacity, failureCount.get());
            assertEquals(0, realZone.getAvailableAmount());
            assertEquals(capacity, realZone.getReservedAmount());

        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    public void GivenValidQuantity_WhenReleaseStanding_ThenTicketsReturnedToAvailableAmount() {
        zone.reserve(InventorySelection.standing(5));

        zone.release(InventorySelection.standing(2));

        assertEquals(7, zone.getAvailableAmount());
        assertEquals(3, zone.getReservedAmount());
    }

    @Test
    public void GivenReleaseMoreThanReserved_WhenReleaseStanding_ThenThrowsException() {
        zone.reserve(InventorySelection.standing(3));

        assertThrows(IllegalStateException.class, () -> zone.release(InventorySelection.standing(4)));
    }

    @Test
    public void GivenInvalidQuantity_WhenReserveStanding_ThenThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> zone.reserve(InventorySelection.standing(0)));
    }

    @Test
    void GivenReservedSeat_WhenReserveAgain_ThenRejected() {
        SeatedZone zone = new SeatedZone(
                1,
                "Orchestra",
                100,
                List.of(new Seat("A1", 0, 0)));

        zone.reserve(InventorySelection.seated(List.of("A1"), "test-order"));

        assertThrows(
                IllegalStateException.class,
                () -> zone.reserve(InventorySelection.seated(List.of("A1"), "test-order")));
    }

    @Test
    void GivenStandingZone_WhenReserveSeatLabels_ThenRejected() {
        StandingZone zone = new StandingZone(1, "General", 100, 50);

        assertThrows(
                IllegalArgumentException.class,
                () -> zone.reserve(InventorySelection.seated(List.of("A1"))));
    }

    @Test
    void GivenSeatedZone_WhenCreated_ThenCapacityAndAvailabilityAreDerivedFromSeats() {
        SeatedZone seatedZone = track(new SeatedZone(
                1,
                "Orchestra",
                120.0,
                List.of(
                        new Seat("A1", 0, 0),
                        new Seat("A2", 1, 0),
                        new Seat("A3", 2, 0))));

        assertEquals(3, seatedZone.getCapacity());
        assertEquals(3, seatedZone.getAvailableAmount());
        assertEquals(0, seatedZone.getReservedAmount());
        assertEquals(0, seatedZone.getSoldAmount());
        assertEquals(ZoneType.SEATED, seatedZone.getZoneType());
    }

    @Test
    void GivenDuplicateSeatLabels_WhenCreateSeatedZone_ThenThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> new SeatedZone(
                1,
                "Orchestra",
                120.0,
                List.of(
                        new Seat("A1", 0, 0),
                        new Seat("A1", 1, 0))));
    }

    @Test
    void GivenAvailableSeats_WhenReserveSeats_ThenSeatsBecomeReserved() {
        SeatedZone seatedZone = track(new SeatedZone(
                1,
                "Orchestra",
                120.0,
                List.of(
                        new Seat("A1", 0, 0),
                        new Seat("A2", 1, 0),
                        new Seat("A3", 2, 0))));

        seatedZone.reserve(InventorySelection.seated(List.of("A1", "A2"), "test-order"));

        assertEquals(SeatStatus.RESERVED, seatedZone.getSeatStatus("A1"));
        assertEquals(SeatStatus.RESERVED, seatedZone.getSeatStatus("A2"));
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A3"));
        assertEquals(1, seatedZone.getAvailableAmount());
        assertEquals(2, seatedZone.getReservedAmount());
    }

    @Test
    void GivenDuplicateSeatLabels_WhenReserveSeats_ThenThrowsAndDoesNotReserveSeat() {
        SeatedZone seatedZone = track(new SeatedZone(
                1,
                "Orchestra",
                120.0,
                List.of(
                        new Seat("A1", 0, 0),
                        new Seat("A2", 1, 0))));

        assertThrows(IllegalArgumentException.class,
                () -> seatedZone.reserve(InventorySelection.seated(List.of("A1", "A1"), "test-order")));

        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A1"));
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A2"));
        assertEquals(2, seatedZone.getAvailableAmount());
        assertEquals(0, seatedZone.getReservedAmount());
    }

    @Test
    void GivenUnknownSeat_WhenReserveSeats_ThenThrowsAndNoSeatIsReserved() {
        SeatedZone seatedZone = track(new SeatedZone(
                1,
                "Orchestra",
                120.0,
                List.of(
                        new Seat("A1", 0, 0),
                        new Seat("A2", 1, 0))));

        assertThrows(IllegalArgumentException.class,
                () -> seatedZone.reserve(InventorySelection.seated(List.of("A1", "Z9"), "test-order")));

        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A1"));
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A2"));
        assertEquals(2, seatedZone.getAvailableAmount());
    }

    @Test
    void GivenReservedSeat_WhenReserveAgain_ThenThrowsException() {
        SeatedZone seatedZone = track(new SeatedZone(
                1,
                "Orchestra",
                120.0,
                List.of(new Seat("A1", 0, 0))));

        seatedZone.reserve(InventorySelection.seated(List.of("A1"), "test-order"));

        assertThrows(IllegalStateException.class,
                () -> seatedZone.reserve(InventorySelection.seated(List.of("A1"), "test-order")));

        assertEquals(SeatStatus.RESERVED, seatedZone.getSeatStatus("A1"));
    }

    @Test
    void GivenReservedSeats_WhenReleaseSeats_ThenSeatsBecomeAvailable() {
        SeatedZone seatedZone = track(new SeatedZone(
                1,
                "Orchestra",
                120.0,
                List.of(
                        new Seat("A1", 0, 0),
                        new Seat("A2", 1, 0))));

        seatedZone.reserve(InventorySelection.seated(List.of("A1", "A2"), "test-order"));
        seatedZone.release(InventorySelection.seated(List.of("A1"), "test-order"));

        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A1"));
        assertEquals(SeatStatus.RESERVED, seatedZone.getSeatStatus("A2"));
        assertEquals(1, seatedZone.getAvailableAmount());
        assertEquals(1, seatedZone.getReservedAmount());
    }

    @Test
    void GivenAvailableSeat_WhenReleaseSeats_ThenThrowsException() {
        SeatedZone seatedZone = track(new SeatedZone(
                1,
                "Orchestra",
                120.0,
                List.of(new Seat("A1", 0, 0))));

        assertThrows(IllegalStateException.class, () -> seatedZone.release(InventorySelection.seated(List.of("A1"))));

        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A1"));
    }

    @Test
    void GivenReservedSeats_WhenConfirmSale_ThenSeatsBecomeSold() {
        SeatedZone seatedZone = track(new SeatedZone(
                1,
                "Orchestra",
                120.0,
                List.of(
                        new Seat("A1", 0, 0),
                        new Seat("A2", 1, 0),
                        new Seat("A3", 2, 0))));

        seatedZone.reserve(InventorySelection.seated(List.of("A1", "A2"), "test-order"));
        seatedZone.confirmSale(InventorySelection.seated(List.of("A1", "A2"), "test-order"));

        assertEquals(SeatStatus.SOLD, seatedZone.getSeatStatus("A1"));
        assertEquals(SeatStatus.SOLD, seatedZone.getSeatStatus("A2"));
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A3"));
        assertEquals(1, seatedZone.getAvailableAmount());
        assertEquals(0, seatedZone.getReservedAmount());
        assertEquals(2, seatedZone.getSoldAmount());
    }

    @Test
    void GivenAvailableSeat_WhenConfirmSaleWithoutReservation_ThenThrowsException() {
        SeatedZone seatedZone = track(new SeatedZone(
                1,
                "Orchestra",
                120.0,
                List.of(new Seat("A1", 0, 0))));

        assertThrows(IllegalStateException.class,
                () -> seatedZone.confirmSale(InventorySelection.seated(List.of("A1"))));

        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A1"));
    }

    @Test
    void GivenStandingZone_WhenReserveSeatSelection_ThenThrowsException() {
        StandingZone standingZone = track(new StandingZone(1, "General Admission", 100, 50.0));

        assertThrows(IllegalArgumentException.class,
                () -> standingZone.reserve(InventorySelection.seated(List.of("A1"))));

        assertEquals(100, standingZone.getAvailableAmount());
    }

    @Test
    void GivenSeatedZone_WhenReserveStandingQuantity_ThenThrowsException() {
        SeatedZone seatedZone = track(new SeatedZone(
                1,
                "Orchestra",
                120.0,
                List.of(new Seat("A1", 0, 0))));

        assertThrows(IllegalArgumentException.class, () -> seatedZone.reserve(InventorySelection.standing(1)));

        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A1"));
    }

    @Test
    void GivenManyThreadsReserveSameSeat_WhenReserve_ThenOnlyOneSucceeds()
            throws InterruptedException {

        SeatedZone seatedZone = track(new SeatedZone(
                1,
                "Orchestra",
                120.0,
                List.of(new Seat("A1", 0, 0))));

        int numberOfThreads = 20;

        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        try {
            for (int i = 0; i < numberOfThreads; i++) {
                executorService.submit(() -> {
                    try {
                        readyLatch.countDown();
                        startLatch.await();

                        seatedZone.reserve(InventorySelection.seated(List.of("A1"), "test-order"));
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

            assertTrue(finished);
            assertEquals(1, successCount.get());
            assertEquals(numberOfThreads - 1, failureCount.get());
            assertEquals(SeatStatus.RESERVED, seatedZone.getSeatStatus("A1"));
            assertEquals(0, seatedZone.getAvailableAmount());
            assertEquals(1, seatedZone.getReservedAmount());

        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void GivenStandingZoneReservedTickets_WhenConfirmSale_ThenReservedDecreasesAndSoldIncreases() {
        StandingZone zone = new StandingZone(1, "General", 100, 50);

        zone.reserve(InventorySelection.standing(3));
        zone.confirmSale(InventorySelection.standing(3));

        assertEquals(0, zone.getReservedAmount());
        assertEquals(3, zone.getSoldAmount());
        assertEquals(97, zone.getAvailableAmount());
    }

    @Test
    void GivenStandingZone_WhenAddPlaces_ThenCapacityAndAvailabilityIncrease() {
        StandingZone zone = new StandingZone(1, "General", 100, 50);

        zone.addPlaces(25);

        assertEquals(125, zone.getCapacity());
        assertEquals(125, zone.getAvailableAmount());
    }

    @Test
    void GivenStandingZoneWithReservations_WhenRemoveMoreThanAvailable_ThenThrowsException() {
        StandingZone zone = new StandingZone(1, "General", 10, 50);
        zone.reserve(InventorySelection.standing(4));

        assertThrows(IllegalArgumentException.class, () -> zone.removePlaces(7));

        assertEquals(10, zone.getCapacity());
        assertEquals(6, zone.getAvailableAmount());
        assertEquals(4, zone.getReservedAmount());
    }

    @Test
    void GivenSeatedZone_WhenAddSeats_ThenCapacityAndAvailabilityIncrease() {
        SeatedZone zone = new SeatedZone(
                1,
                "Orchestra",
                100,
                List.of(new Seat("A1", 0, 0)));

        zone.addSeats(List.of(
                new Seat("A2", 1, 0),
                new Seat("A3", 2, 0)));

        assertEquals(3, zone.getCapacity());
        assertEquals(3, zone.getAvailableAmount());
        assertEquals(SeatStatus.AVAILABLE, zone.getSeatStatus("A2"));
    }

    @Test
    void GivenSeatedZone_WhenRemoveAvailableSeat_ThenCapacityDecreases() {
        SeatedZone zone = new SeatedZone(
                1,
                "Orchestra",
                100,
                List.of(
                        new Seat("A1", 0, 0),
                        new Seat("A2", 1, 0)));

        zone.removeSeats(List.of("A2"));

        assertEquals(1, zone.getCapacity());
        assertThrows(IllegalArgumentException.class, () -> zone.getSeatStatus("A2"));
    }

    @Test
    void GivenReservedSeat_WhenRemoveSeat_ThenThrowsException() {
        SeatedZone zone = new SeatedZone(
                1,
                "Orchestra",
                100,
                List.of(new Seat("A1", 0, 0)));

        zone.reserve(InventorySelection.seated(List.of("A1"), "test-order"));

        assertThrows(IllegalStateException.class, () -> zone.removeSeats(List.of("A1")));

        assertEquals(1, zone.getCapacity());
        assertEquals(SeatStatus.RESERVED, zone.getSeatStatus("A1"));
    }

    // -- returnSoldToStock (member refund: SOLD -> AVAILABLE) -----------------

    @Test
    void GivenSoldSeats_WhenReturnSoldToStock_ThenSeatsBecomeAvailable() {
        SeatedZone seatedZone = track(new SeatedZone(1, "Orchestra", 120.0,
                List.of(new Seat("A1", 0, 0), new Seat("A2", 1, 0))));
        seatedZone.reserve(InventorySelection.seated(List.of("A1"), "ord"));
        seatedZone.confirmSale(InventorySelection.seated(List.of("A1"), "ord"));

        seatedZone.returnSoldToStock(InventorySelection.seated(List.of("A1")));

        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A1"));
        assertEquals(2, seatedZone.getAvailableAmount());
        assertEquals(0, seatedZone.getSoldAmount());
    }

    @Test
    void GivenNonSoldSeat_WhenReturnSoldToStock_ThenThrows() {
        SeatedZone seatedZone = track(new SeatedZone(1, "Orchestra", 120.0,
                List.of(new Seat("A1", 0, 0))));

        assertThrows(IllegalStateException.class,
                () -> seatedZone.returnSoldToStock(InventorySelection.seated(List.of("A1"))));

        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A1"));
    }

    @Test
    void GivenStandingSold_WhenReturnSoldToStock_ThenSoldDecreasesAndAvailableIncreases() {
        StandingZone standing = track(new StandingZone(1, "General", 100, 50));
        standing.reserve(InventorySelection.standing(3));
        standing.confirmSale(InventorySelection.standing(3));

        standing.returnSoldToStock(InventorySelection.standing(2));

        assertEquals(1, standing.getSoldAmount());
        assertEquals(99, standing.getAvailableAmount());
    }

    @Test
    void GivenStandingReturnMoreThanSold_WhenReturnSoldToStock_ThenThrows() {
        StandingZone standing = track(new StandingZone(1, "General", 100, 50));
        standing.reserve(InventorySelection.standing(2));
        standing.confirmSale(InventorySelection.standing(2));

        assertThrows(IllegalStateException.class,
                () -> standing.returnSoldToStock(InventorySelection.standing(3)));

        assertEquals(2, standing.getSoldAmount());
    }

    // -- grid placement ---------------------------------------------------

    @Test
    void GivenValidPlacement_WhenPlaceOnGrid_ThenGettersAndHasPlacementReflectIt() {
        InventoryZone gridZone = track(new StandingZone(ZONE_ID, "Grid VIP", 10, 100));

        assertFalse(gridZone.hasGridPlacement());

        gridZone.placeOnGrid(2, 3, 1, 2);

        assertTrue(gridZone.hasGridPlacement());
        assertEquals(2, gridZone.getGridRow());
        assertEquals(3, gridZone.getGridCol());
        assertEquals(1, gridZone.getGridRowSpan());
        assertEquals(2, gridZone.getGridColSpan());
    }

    @Test
    void GivenRowBelowOne_WhenPlaceOnGrid_ThenThrowsIllegalArgument() {
        InventoryZone gridZone = track(new StandingZone(ZONE_ID, "Grid VIP", 10, 100));

        assertThrows(IllegalArgumentException.class, () -> gridZone.placeOnGrid(0, 1, 1, 1));
        assertFalse(gridZone.hasGridPlacement());
    }

    @Test
    void GivenColBelowOne_WhenPlaceOnGrid_ThenThrowsIllegalArgument() {
        InventoryZone gridZone = track(new StandingZone(ZONE_ID, "Grid VIP", 10, 100));

        assertThrows(IllegalArgumentException.class, () -> gridZone.placeOnGrid(1, 0, 1, 1));
        assertFalse(gridZone.hasGridPlacement());
    }

    @Test
    void GivenSpanBelowOne_WhenPlaceOnGrid_ThenThrowsIllegalArgument() {
        InventoryZone gridZone = track(new StandingZone(ZONE_ID, "Grid VIP", 10, 100));

        assertThrows(IllegalArgumentException.class, () -> gridZone.placeOnGrid(1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> gridZone.placeOnGrid(1, 1, 1, 0));
        assertFalse(gridZone.hasGridPlacement());
    }

}