package com.ticketing.system.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.catalog.domain.DiscountPolicy;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.domain.InventoryZone;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.Seat;
import com.ticketing.system.catalog.domain.SeatedZone;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.catalog.domain.StandingZone;
import com.ticketing.system.catalog.domain.VenueMap;
import com.ticketing.system.shared.domain.policy.NoPurchasePolicy;
import com.ticketing.system.shared.exception.ConcurrentReservationException;
import com.ticketing.system.shared.exception.DomainException;
import com.ticketing.system.sales.adapter.out.persistence.SpringDataActiveOrderRepository;
import com.ticketing.system.catalog.adapter.out.persistence.SpringDataEventRepository;
import com.ticketing.system.organization.adapter.out.persistence.SpringDataProductionCompanyRepository;

/**
 * No-double-sell concurrency proof (V3-TX-03 / #361), run on the real JPA stack.
 *
 * <p>The contract tests cover reservation <em>behaviour</em> but not concurrency. This test fires N
 * buyers at a single unit of inventory at once and asserts <strong>exactly one wins</strong> with no
 * oversell — the guarantee that {@code @Version} optimistic locking is supposed to provide. It must
 * run on the Jpa adapters, because the in-memory repos serialise via real locks (a different
 * mechanism); only Hibernate's {@code @Version} check at commit is exercised here.
 *
 * <p>Two granularities, deliberately different:
 * <ul>
 *   <li><b>Seated</b> — N buyers contend the <em>same seat</em>; the conflict is resolved by
 *       {@code Seat.version}.</li>
 *   <li><b>Standing</b> — N buyers each take one place from a <em>capacity-1</em> zone with distinct
 *       order keys, so there is no row-level collision on the reservation collection; the only guard
 *       is Hibernate bumping the owning zone's {@code @Version} when that collection changes.</li>
 * </ul>
 *
 * <p>The snapshot tests then hold two buyers just after each has loaded the event, and so the zone, until
 * the other has too. Both see the same free unit, so the zone's own check passes for both, and only the
 * {@code @Version} check at commit can refuse one of them.
 *
 * <p>Profile {@code test} without {@code memory}: JPA repositories on, Memory repos off, the
 * in-process stub payment/issuer adapters on (so the context wires), and H2 from
 * {@code application-test.yml}. The
 * market gate is mocked open — it is not what this test proves.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext // its bean overrides give this class a context no other class reuses
class ReservationConcurrencyTest {

    private static final int CONCURRENT_BUYERS = 8;
    private static final int COMPANY_ID = 100;
    private static final String CONTESTED_SEAT = "A1";
    // How long a worker waits for the start signal: one that never gets it ends instead of waiting forever.
    private static final int FIRE_TIMEOUT_SECONDS = 10;
    private static final int SNAPSHOT_HOLDERS = 2;
    // How long a snapshot holder waits for the other to load the event, and the test for each reservation
    // to finish.
    private static final long SNAPSHOT_AWAIT_SECONDS = 20;

    @Autowired
    private ReservationService reservationService;
    // Mints the live guest sessions the buyers reserve on; reserveForGuest accepts no other id (#185).
    @Autowired
    private AuthenticationService authenticationService;
    // The Jpa adapter, spied on only to hold the snapshot tests' buyers once each has loaded the event.
    @MockitoSpyBean
    private EventRepository eventRepository;
    @Autowired
    private ProductionCompanyRepository companyRepository;

    // SpringData handles for an empty-schema reset between tests.
    @Autowired
    private SpringDataEventRepository eventData;
    @Autowired
    private SpringDataProductionCompanyRepository companyData;
    @Autowired
    private SpringDataActiveOrderRepository activeOrderData;

    // Reserve checks the platform market is OPEN before touching inventory; the market is not what
    // this test proves, so it is mocked open.
    @MockitoBean
    private SystemAdminService systemAdminService;

    private int eventId;
    private int seatedZoneId;
    private int standingZoneId;

    @BeforeEach
    void setUp() {
        // A full @SpringBootTest does not auto-rollback, and concurrency workers must see committed
        // data, so each test starts from an explicitly emptied schema.
        activeOrderData.deleteAll();
        eventData.deleteAll();
        companyData.deleteAll();

        when(systemAdminService.isMarketOpen()).thenReturn(true);

        // Reserve resolves the event's company + its purchase policy; a permissive NoPurchasePolicy
        // on both the company (its default) and the event lets any guest reserve one unit.
        companyRepository.save(new ProductionCompany(COMPANY_ID, 1, "Concurrency Test Co",
                CompanyStatus.ACTIVE, "test company", 5.0));

        eventId = eventRepository.nextId();
        seatedZoneId = 1;
        standingZoneId = 2;
        SeatedZone seated = new SeatedZone(seatedZoneId, "Stage", 50.0, List.of(new Seat(CONTESTED_SEAT, 0, 0)));
        StandingZone standing = new StandingZone(standingZoneId, "Floor", 1, 40.0); // capacity 1
        VenueMap venue = new VenueMap(eventRepository.nextVenueMapId(),
                new Location("Israel", "Beer Sheva"), List.of(seated, standing));
        Event event = new Event(eventId, "Concurrency Concert", 4.5, List.of("Artist"),
                EventCategory.MUSIC, COMPANY_ID, EventStatus.ON_SALE, venue,
                List.of(new ShowDate(LocalDateTime.of(2099, 6, 1, 18, 0), LocalDateTime.of(2099, 6, 1, 22, 0))),
                ZoneId.of("Asia/Jerusalem"),
                new NoPurchasePolicy(), new DiscountPolicy(0));
        eventRepository.save(event);
    }

    @Test
    void GivenManyGuestsAndOneContestedSeat_WhenReserveConcurrently_ThenExactlyOneWinsAndTheSeatIsNotOversold()
            throws InterruptedException {
        Outcome outcome = runConcurrentReserves(seatedZoneId,
                InventorySelectionDTO.seated(List.of(CONTESTED_SEAT)));

        assertExactlyOneWinner(outcome);

        SeatedZone zone = (SeatedZone) reloadZone(SeatedZone.class);
        assertEquals(1, zone.getReservedAmount(), "the single seat is reserved exactly once");
        assertEquals(0, zone.getAvailableAmount(), "no seats remain available — no oversell");
    }

    @Test
    void GivenManyGuestsAndOneStandingPlace_WhenReserveConcurrently_ThenExactlyOneWinsAndTheZoneIsNotOversold()
            throws InterruptedException {
        Outcome outcome = runConcurrentReserves(standingZoneId, InventorySelectionDTO.standing(1));

        assertExactlyOneWinner(outcome);

        StandingZone zone = (StandingZone) reloadZone(StandingZone.class);
        assertEquals(1, zone.getReservedAmount(), "the single standing place is reserved exactly once");
        assertEquals(0, zone.getAvailableAmount(), "no capacity remains — no oversell");
    }

    @Test
    void GivenTwoGuestsWhoBothLoadedTheOneFreeSeat_WhenBothReserveIt_ThenOneLosesAsAConcurrentChangeAndTheSeatIsHeldOnce()
            throws InterruptedException {
        List<Throwable> outcomes = reserveFromOneSnapshot(seatedZoneId,
                InventorySelectionDTO.seated(List.of(CONTESTED_SEAT)));

        assertOneWonAndTheOtherLostAsAConcurrentChange(outcomes);
        SeatedZone zone = (SeatedZone) reloadZone(SeatedZone.class);
        assertEquals(1, zone.getReservedAmount(), "the single seat is reserved exactly once");
        assertEquals(1, activeOrderData.count(), "the loser's cart rolled back with its reservation");
    }

    @Test
    void GivenTwoGuestsWhoBothLoadedTheOneFreeStandingPlace_WhenBothReserveIt_ThenOneLosesAsAConcurrentChangeAndThePlaceIsHeldOnce()
            throws InterruptedException {
        List<Throwable> outcomes = reserveFromOneSnapshot(standingZoneId, InventorySelectionDTO.standing(1));

        assertOneWonAndTheOtherLostAsAConcurrentChange(outcomes);
        StandingZone zone = (StandingZone) reloadZone(StandingZone.class);
        assertEquals(1, zone.getReservedAmount(), "the single standing place is reserved exactly once");
        assertEquals(1, activeOrderData.count(), "the loser's cart rolled back with its reservation");
    }

    /**
     * Two guests reserve the same unit, each held after loading the event until the other has loaded it
     * too; each outcome is {@code null} for a success, else what it threw. A holder whose partner never
     * arrives fails with the barrier's timeout, so a run in which the two never shared a snapshot fails.
     */
    private List<Throwable> reserveFromOneSnapshot(int zoneId, InventorySelectionDTO selection)
            throws InterruptedException {
        CyclicBarrier bothLoaded = new CyclicBarrier(SNAPSHOT_HOLDERS);
        Set<Thread> held = ConcurrentHashMap.newKeySet();
        AtomicBoolean racing = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object loaded = invocation.callRealMethod();
            if (racing.get() && held.add(Thread.currentThread())) {
                bothLoaded.await(SNAPSHOT_AWAIT_SECONDS, TimeUnit.SECONDS);
            }
            return loaded;
        }).when(eventRepository).findById(eventId);

        ExecutorService pool = Executors.newFixedThreadPool(SNAPSHOT_HOLDERS);
        try {
            // Minted before the workers start, so the session writes are not part of the race.
            List<String> guestSessionIds = new ArrayList<>();
            for (int i = 0; i < SNAPSHOT_HOLDERS; i++) {
                guestSessionIds.add(authenticationService.startGuestSession().sessionId());
            }
            List<Future<?>> reservations = new ArrayList<>();
            for (String guestSessionId : guestSessionIds) {
                reservations.add(pool.submit(
                        () -> reservationService.reserveForGuest(guestSessionId, eventId, zoneId, selection)));
            }
            List<Throwable> outcomes = new ArrayList<>();
            for (Future<?> reservation : reservations) {
                outcomes.add(outcomeOf(reservation));
            }
            assertEquals(SNAPSHOT_HOLDERS, held.size(), "both buyers must have been held after loading the event");
            return outcomes;
        } finally {
            racing.set(false);
            pool.shutdownNow();
        }
    }

    private static Throwable outcomeOf(Future<?> reservation) throws InterruptedException {
        try {
            reservation.get(SNAPSHOT_AWAIT_SECONDS, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (TimeoutException e) {
            return e;
        }
    }

    // A reservation that lost to another can be refused in three ways: by the zone, an IllegalStateException
    // when it loaded the zone after the winner committed; by the event, an IllegalStateException once the
    // winner's reservation sold the event out (not here, as the venue keeps the other zone's unit); or by the
    // version check at commit, a ConcurrentReservationException, when both loaded it first. Holding both
    // after the load leaves only the last.
    private static void assertOneWonAndTheOtherLostAsAConcurrentChange(List<Throwable> outcomes) {
        List<Throwable> failures = outcomes.stream().filter(Objects::nonNull).toList();
        assertEquals(1, failures.size(), "exactly one of the two buyers may win the unit, but the outcomes were "
                + outcomes);
        assertInstanceOf(ConcurrentReservationException.class, failures.get(0),
                "the loser held the same snapshot, so only the version check at commit may refuse it, but it "
                        + "failed with " + failures.get(0));
    }

    /** Fires CONCURRENT_BUYERS guest reservations at the same unit simultaneously and tallies them. */
    private Outcome runConcurrentReserves(int zoneId, InventorySelectionDTO selection)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_BUYERS);
        CountDownLatch armed = new CountDownLatch(CONCURRENT_BUYERS);
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(CONCURRENT_BUYERS);
        AtomicInteger successes = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        try {
            for (int i = 0; i < CONCURRENT_BUYERS; i++) {
                // Minted before the workers start, so the session writes are not part of the race.
                String guestSessionId = authenticationService.startGuestSession().sessionId();
                pool.submit(() -> {
                    try {
                        armed.countDown();
                        // release all workers at once to maximise the race window
                        if (!fire.await(FIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("the race was never started");
                        }
                        reservationService.reserveForGuest(guestSessionId, eventId, zoneId, selection);
                        successes.incrementAndGet();
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        finished.countDown();
                    }
                });
            }

            assertTrue(armed.await(5, TimeUnit.SECONDS), "workers did not arm in time");
            fire.countDown();
            assertTrue(finished.await(20, TimeUnit.SECONDS), "reservations did not finish in time");
        } finally {
            // Interrupts the workers even when an assertion above failed. One blocked where an interrupt does not
            // reach, such as a database call, can still outlast the test.
            pool.shutdownNow();
        }
        return new Outcome(successes.get(), failures);
    }

    private void assertExactlyOneWinner(Outcome outcome) {
        assertEquals(1, outcome.successes(),
                "exactly one buyer may win the single unit (got " + outcome.successes()
                        + " — more than one means an oversell)");
        assertEquals(CONCURRENT_BUYERS - 1, outcome.failures().size(),
                "every other buyer must fail cleanly; failures: " + outcome.failures());
        // A loser that read the zone before the winner committed fails the version check
        // (ConcurrentReservationException); one that read it after is refused by the zone itself, which throws
        // IllegalStateException until #219 retypes rule violations. A dropped connection, a lock timeout (both
        // DataAccessExceptions) or an NPE is no clean loss.
        List<Throwable> unclean = outcome.failures().stream()
                .filter(failure -> !(failure instanceof DomainException || failure instanceof IllegalStateException))
                .toList();
        assertTrue(unclean.isEmpty(), "every other buyer must be refused by the domain, but got: " + unclean);
    }

    private InventoryZone reloadZone(Class<? extends InventoryZone> zoneType) {
        return eventRepository.findById(eventId).getVenueMap().getInventoryZones().stream()
                .filter(zoneType::isInstance)
                .findFirst()
                .orElseThrow();
    }

    private record Outcome(int successes, ConcurrentLinkedQueue<Throwable> failures) {
    }
}
