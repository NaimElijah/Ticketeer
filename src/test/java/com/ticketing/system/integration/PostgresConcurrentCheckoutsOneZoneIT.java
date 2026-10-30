package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.catalog.application.port.in.InventoryCommandPort;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.integration.SeatedShowFixture.Show;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CardDetailsDTO;
import com.ticketing.system.support.PostgresContainers;

/**
 * Two buyers check out in one zone at the same moment (SLR.1, SLR.5). On PostgreSQL their Phase 3s share
 * the event's lock and run together. In a standing zone both write the zone's own counts, so whichever
 * commits second finds the zone's version moved, and if nothing retries, its checkout fails after the
 * charge: the hold is released and the charge refunded, though the zone had room. Seated sales write
 * only their own seat rows, so they never meet; that case is kept as a guard.
 *
 * <p>Both checkouts are held inside Phase 3, after the on-sale check, until each has reached it. Runs
 * on PostgreSQL only: H2 takes an exclusive lock for the event's shared one, so there the two Phase 3s
 * run one after the other and never meet.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext
class PostgresConcurrentCheckoutsOneZoneIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    private static final String CURRENCY = "ILS";
    private static final CardDetailsDTO CARD = new CardDetailsDTO("4111111111111234", "123", 12, 2030, "Test Holder");
    private static final int BUYERS = 2;
    private static final int STANDING_CAPACITY = 10;
    private static final long AWAIT_SECONDS = 30;

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private CatalogService catalogService;
    @Autowired private SystemAdminService systemAdminService;
    @Autowired private ReservationService reservationService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private Clock clock;

    // The catalog port, spied on only to hold each checkout in Phase 3 until both have reached it.
    @MockitoSpyBean private InventoryCommandPort inventoryPort;

    private SeatedShowFixture fixture;

    @BeforeEach
    void openMarket() {
        fixture = new SeatedShowFixture(authService, companyService, eventManagementService, catalogService,
                systemAdminService, clock);
        fixture.ensureMarketOpen();
    }

    // A seated sale writes only its own seat rows, so two sales in one zone do not meet.
    @Test
    void GivenTwoBuyersOfDifferentSeatsInOneZone_WhenBothCheckOutAtOnce_ThenBothPurchasesSucceed() throws Exception {
        Show show = fixture.publishSeatedShow();
        List<AuthTokenDTO> buyers = new ArrayList<>();
        for (int i = 0; i < BUYERS; i++) {
            AuthTokenDTO buyer = fixture.registerAndLogin("zoneBuyer" + i);
            reservationService.reserveForMember(buyer.token(), show.eventId(), show.zoneId(),
                    InventorySelectionDTO.seated(List.of(SeatedShowFixture.SEATS.get(i))));
            buyers.add(buyer);
        }
        holdEachCheckoutInPhase3UntilBothArrive();

        List<Throwable> failures = checkOutAtOnce(buyers);

        assertEquals(List.of(), failures, "both seats were free, so both purchases must succeed");
        assertEquals(SeatedShowFixture.SEATS.size() - BUYERS, fixture.availableSeats(show));
    }

    // A standing sale moves the zone's own reserved and sold counts, so the two sales write one row.
    @Test
    void GivenTwoBuyersOfOneStandingZone_WhenBothCheckOutAtOnce_ThenBothPurchasesSucceed() throws Exception {
        Show show = fixture.publishStandingShow(STANDING_CAPACITY);
        List<AuthTokenDTO> buyers = new ArrayList<>();
        for (int i = 0; i < BUYERS; i++) {
            AuthTokenDTO buyer = fixture.registerAndLogin("standingBuyer" + i);
            reservationService.reserveForMember(buyer.token(), show.eventId(), show.zoneId(),
                    InventorySelectionDTO.standing(1));
            buyers.add(buyer);
        }
        holdEachCheckoutInPhase3UntilBothArrive();

        List<Throwable> failures = checkOutAtOnce(buyers);

        assertEquals(List.of(), failures, "the zone had room for both, so both purchases must succeed");
        assertEquals(STANDING_CAPACITY - BUYERS, fixture.availableSeats(show));
    }

    // Only the first Phase 3 of each checkout waits, so a retried Phase 3 runs straight through.
    private void holdEachCheckoutInPhase3UntilBothArrive() {
        CyclicBarrier bothInPhase3 = new CyclicBarrier(BUYERS);
        java.util.Set<Thread> held = java.util.concurrent.ConcurrentHashMap.newKeySet();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (held.add(Thread.currentThread())) {
                bothInPhase3.await(AWAIT_SECONDS, TimeUnit.SECONDS);
            }
            return null;
        }).when(inventoryPort).validateEventsOnSale(anyList());
    }

    private List<Throwable> checkOutAtOnce(List<AuthTokenDTO> buyers) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(buyers.size());
        try {
            List<Future<?>> checkouts = new ArrayList<>();
            for (AuthTokenDTO buyer : buyers) {
                checkouts.add(pool.submit(() -> checkoutService.checkoutMember(
                        buyer.token(), "zone-" + UUID.randomUUID(), CURRENCY, CARD)));
            }
            List<Throwable> failures = new ArrayList<>();
            for (Future<?> checkout : checkouts) {
                try {
                    checkout.get(AWAIT_SECONDS, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                } catch (java.util.concurrent.TimeoutException e) {
                    failures.add(e);
                }
            }
            return failures;
        } finally {
            pool.shutdownNow();
        }
    }
}
