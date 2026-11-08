package com.ticketing.system.integration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
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
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.exception.ConcurrentReservationException;

/**
 * Business rule §1 #8, one active order per buyer, under two first reservations at once: a buyer with
 * no cart reserves from two tabs at the same moment, as a member or from one guest session. On the Jpa
 * adapters the order lock is a no-op, so both requests find no cart and each creates its own. Without a
 * database rule, either both carts commit, or the later save deletes the earlier cart and leaves its seat
 * reserved under an order key no cart holds, which nothing ever releases. A member's carts are held to
 * one by {@code uk_active_orders_user_id}, a guest session's by {@code uk_active_orders_guest_session_id}
 * (#224).
 *
 * <p>The two reservations are for two different shows, so they share no inventory row and neither can
 * fail on an inventory version conflict. The first request to save its cart is held, uncommitted, until
 * the other has saved too or waits on a lock behind it, so the other's save can never see the first cart
 * and only the database key can refuse it. Runs on the Jpa adapters over H2.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext // its bean overrides give this class a context no other class reuses
class ConcurrentFirstReservationJpaTest {

    private static final String SEAT = SeatedShowFixture.SEATS.get(0);
    private static final int ATTEMPTS = 2;
    private static final long AWAIT_SECONDS = 20;
    private static final Duration LOCK_WAIT_POLL = Duration.ofMillis(10);
    private static final String MEMBER_CART_KEY = "uk_active_orders_user_id";
    private static final String GUEST_SESSION_CART_KEY = "uk_active_orders_guest_session_id";

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private CatalogService catalogService;
    @Autowired private SystemAdminService systemAdminService;
    @Autowired private ReservationService reservationService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    // The Jpa adapter, spied on only to hold both requests until each has looked for a cart and found none,
    // and the first to save its cart until the other has saved too.
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
        Set<Thread> held = ConcurrentHashMap.newKeySet();
        doAnswer(holdEachUntilBothFoundNoCart(held, Objects::isNull))
                .when(activeOrderRepository).getByUserId(member.userId());
        holdTheFirstCartSaveUntilTheOtherSaveRan(
                cart -> cart != null && Integer.valueOf(member.userId()).equals(cart.userIdOrNull()));

        List<Throwable> outcomes = reserveAtOnce(List.of(first, second), show -> reservationService.reserveForMember(
                member.token(), show.eventId(), show.zoneId(), InventorySelectionDTO.seated(List.of(SEAT))));

        assertOneWonAndTheOtherFailedOn(MEMBER_CART_KEY, held, outcomes);
        assertEquals(1, jdbc.queryForObject("select count(*) from active_orders where user_id = ?", Integer.class,
                member.userId()), "the member has one cart");
        assertNoSeatIsLeftWithoutACart(first, second);
    }

    // #224: a guest's two tabs share one guest session, so both requests look the cart up by that session.
    @Test
    void GivenAGuestSessionWithNoCart_WhenTwoFirstReservationsRunAtOnce_ThenOneFailsAsConcurrentAndTheSessionHasOneCart()
            throws Exception {
        Show first = fixture.publishSeatedShow();
        Show second = fixture.publishSeatedShow();
        String guestSessionId = authService.startGuestSession().sessionId();
        Set<Thread> held = ConcurrentHashMap.newKeySet();
        doAnswer(holdEachUntilBothFoundNoCart(held, cart -> ((Optional<?>) cart).isEmpty()))
                .when(activeOrderRepository).getBySessionId(guestSessionId);
        holdTheFirstCartSaveUntilTheOtherSaveRan(
                cart -> cart != null && cart.isGuest() && guestSessionId.equals(cart.getSessionId()));

        List<Throwable> outcomes = reserveAtOnce(List.of(first, second), show -> reservationService.reserveForGuest(
                guestSessionId, show.eventId(), show.zoneId(), InventorySelectionDTO.seated(List.of(SEAT))));

        assertOneWonAndTheOtherFailedOn(GUEST_SESSION_CART_KEY, held, outcomes);
        assertEquals(1, jdbc.queryForObject("select count(*) from active_orders where session_id = ?"
                + " and user_id is null", Integer.class, guestSessionId), "the guest session has one cart");
        assertNoSeatIsLeftWithoutACart(first, second);
    }

    // Each request waits, once, after its first cart lookup finds nothing, until the other has looked too.
    // A request that never finds an empty cart leaves the other waiting until the barrier times out.
    private Answer<Object> holdEachUntilBothFoundNoCart(Set<Thread> held, Predicate<Object> foundNoCart) {
        CyclicBarrier bothLookedUp = new CyclicBarrier(ATTEMPTS);
        return invocation -> {
            Object cart = invocation.callRealMethod();
            if (foundNoCart.test(cart) && held.add(Thread.currentThread())) {
                bothLookedUp.await(AWAIT_SECONDS, TimeUnit.SECONDS);
            }
            return cart;
        };
    }

    // Each request's first save of the buyer's cart is the one that writes it; a later save on the same thread
    // is the loser's rollback, which runs unheld. The first save to return, its cart written but not committed,
    // waits until the other save has returned or thrown, or is inside its cart's INSERT: past its own lookup,
    // so it can no longer see this cart. That INSERT waits on this cart's uncommitted key entry: on PostgreSQL
    // until this transaction ends, on H2 for up to its 2 s lock timeout, far longer than the hold lasts.
    private void holdTheFirstCartSaveUntilTheOtherSaveRan(ArgumentMatcher<ActiveOrder> buyersCart) {
        Set<Thread> saving = ConcurrentHashMap.newKeySet();
        AtomicReference<Thread> heldSave = new AtomicReference<>();
        CountDownLatch otherSaveRan = new CountDownLatch(1);
        doAnswer(invocation -> {
            boolean firstSaveOfThisRequest = saving.add(Thread.currentThread());
            try {
                return invocation.callRealMethod();
            } finally {
                if (firstSaveOfThisRequest) {
                    if (heldSave.compareAndSet(null, Thread.currentThread())) {
                        await().atMost(Duration.ofSeconds(AWAIT_SECONDS)).pollDelay(Duration.ZERO)
                                .pollInterval(LOCK_WAIT_POLL)
                                .until(() -> otherSaveRan.getCount() == 0 || aSessionIsInsideACartInsert());
                    } else {
                        otherSaveRan.countDown();
                    }
                }
            }
        }).when(activeOrderRepository).save(argThat(buyersCart));
    }

    /**
     * Whether some connection is running a cart INSERT, as H2 reports it. H2 leaves {@code blocker_id} null
     * while an INSERT waits on another transaction's uncommitted unique key entry, so the lock-wait check the
     * refund and cancel races use never sees this wait; the statement the session is running does.
     */
    protected boolean aSessionIsInsideACartInsert() {
        return count("select count(*) from information_schema.sessions"
                + " where lower(executing_statement) like 'insert into active_orders %'") > 0;
    }

    protected int count(String sql) {
        Integer count = jdbc.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    // The loser's save cannot see the winner's uncommitted cart, so only the database key can refuse it. A
    // refusal by the adapter's own lookup would mean the winner had committed first and the race never ran.
    private static void assertOneWonAndTheOtherFailedOn(String buyerKey, Set<Thread> held, List<Throwable> outcomes) {
        assertEquals(ATTEMPTS, held.size(), "both requests must find no cart before either stores one, but "
                + held.size() + " did; the outcomes were " + outcomes);
        List<Throwable> failures = outcomes.stream().filter(Objects::nonNull).toList();
        assertEquals(1, failures.size(), "exactly one of the two first reservations may win, but the outcomes were "
                + outcomes);
        assertInstanceOf(ConcurrentReservationException.class, failures.get(0),
                "the other fails as a retryable concurrent change, but failed with " + failures.get(0));
        assertTrue(causeChainNames(failures.get(0), buyerKey),
                "the other is refused by " + buyerKey + ", but its causes were " + causeChainOf(failures.get(0)));
    }

    private static boolean causeChainNames(Throwable failure, String constraint) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(constraint)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> causeChainOf(Throwable failure) {
        List<String> chain = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            chain.add(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
        return chain;
    }

    private void assertNoSeatIsLeftWithoutACart(Show first, Show second) {
        assertEquals(0, jdbc.queryForObject("select count(*) from seats where status = 'RESERVED'"
                        + " and reserved_by_order_key not in (select order_key from active_orders)", Integer.class),
                "no seat stays reserved under an order key that no cart holds");
        assertEquals(2 * SeatedShowFixture.SEATS.size() - 1, fixture.availableSeats(first) + fixture.availableSeats(second),
                "only the winning reservation holds a seat; the loser's is back in stock");
    }

    /** Runs {@code reserve} for each show at once; each outcome is {@code null} for a success, else what it threw. */
    private List<Throwable> reserveAtOnce(List<Show> shows, Function<Show, ?> reserve) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(shows.size());
        try {
            List<Future<?>> reservations = new ArrayList<>();
            for (Show show : shows) {
                reservations.add(pool.submit(() -> {
                    start.await();
                    return reserve.apply(show);
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
