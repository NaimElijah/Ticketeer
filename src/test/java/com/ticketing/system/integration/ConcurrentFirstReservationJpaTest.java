package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.doAnswer;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.exception.ConcurrentReservationException;

/**
 * Business rule §1 #8, one active order per buyer, under two first reservations at once: a member with
 * no cart reserves from two tabs at the same moment. On the Jpa adapters the order lock is a no-op, so
 * both requests find no cart and each creates its own. Without a database rule, either both carts
 * commit, or the later save deletes the earlier cart and leaves its seat reserved under an order key no
 * cart holds, which nothing ever releases.
 *
 * <p>The two reservations are for two different shows, so they share no inventory row and neither can
 * fail on an inventory version conflict. Runs on the Jpa adapters over H2.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentFirstReservationJpaTest {

    private static final String SEAT = SeatedShowFixture.SEATS.get(0);
    private static final int ATTEMPTS = 2;
    private static final long AWAIT_SECONDS = 20;

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private CatalogService catalogService;
    @Autowired private SystemAdminService systemAdminService;
    @Autowired private ReservationService reservationService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    // The Jpa adapter, spied on only to hold both requests until each has looked for a cart and found none.
    @MockitoSpyBean private ActiveOrderRepository activeOrderRepository;

    private SeatedShowFixture fixture;

    @BeforeEach
    void openMarket() {
        fixture = new SeatedShowFixture(authService, companyService, eventManagementService, catalogService,
                systemAdminService, clock);
        fixture.ensureMarketOpen();
    }

    @Test
    void GivenAMemberWithNoCart_WhenTwoFirstReservationsRunAtOnce_ThenOneFailsAsConcurrentAndNoSeatIsLeftWithoutACart()
            throws Exception {
        Show first = fixture.publishSeatedShow();
        Show second = fixture.publishSeatedShow();
        AuthTokenDTO member = fixture.registerAndLogin("twoTabs");
        holdBothUntilEachFoundNoCart(member.userId());

        List<Throwable> outcomes = reserveAtOnce(member, List.of(first, second));

        List<Throwable> failures = outcomes.stream().filter(Objects::nonNull).toList();
        assertEquals(1, failures.size(), "exactly one of the two first reservations may win, but the outcomes were "
                + outcomes);
        assertInstanceOf(ConcurrentReservationException.class, failures.get(0),
                "the other fails as a retryable concurrent change, but failed with " + failures.get(0));
        assertEquals(1, jdbc.queryForObject("select count(*) from active_orders where user_id = ?", Integer.class,
                member.userId()), "the member has one cart");
        assertEquals(0, jdbc.queryForObject("select count(*) from seats where status = 'RESERVED'"
                        + " and reserved_by_order_key not in (select order_key from active_orders)", Integer.class),
                "no seat stays reserved under an order key that no cart holds");
        assertEquals(2 * SeatedShowFixture.SEATS.size() - 1, fixture.availableSeats(first) + fixture.availableSeats(second),
                "only the winning reservation holds a seat; the loser's is back in stock");
    }

    // Each request waits, once, after its first cart lookup finds nothing, until the other has looked too.
    private void holdBothUntilEachFoundNoCart(int userId) {
        CyclicBarrier bothLookedUp = new CyclicBarrier(ATTEMPTS);
        Set<Thread> held = ConcurrentHashMap.newKeySet();
        doAnswer(invocation -> {
            Object cart = invocation.callRealMethod();
            if (cart == null && held.add(Thread.currentThread())) {
                bothLookedUp.await(AWAIT_SECONDS, TimeUnit.SECONDS);
            }
            return cart;
        }).when(activeOrderRepository).getByUserId(userId);
    }

    /** Reserves one seat of each show at once; each outcome is {@code null} for a success, else what it threw. */
    private List<Throwable> reserveAtOnce(AuthTokenDTO member, List<Show> shows) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(shows.size());
        try {
            List<Future<?>> reservations = new ArrayList<>();
            for (Show show : shows) {
                reservations.add(pool.submit(() -> {
                    start.await();
                    return reservationService.reserveForMember(member.token(), show.eventId(), show.zoneId(),
                            InventorySelectionDTO.seated(List.of(SEAT)));
                }));
            }
            start.countDown();
            List<Throwable> outcomes = new ArrayList<>();
            for (Future<?> reservation : reservations) {
                try {
                    reservation.get(AWAIT_SECONDS, TimeUnit.SECONDS);
                    outcomes.add(null);
                } catch (ExecutionException e) {
                    outcomes.add(e.getCause());
                } catch (java.util.concurrent.TimeoutException e) {
                    outcomes.add(e);
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
