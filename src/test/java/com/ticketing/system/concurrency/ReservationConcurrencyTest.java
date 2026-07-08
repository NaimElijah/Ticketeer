package com.ticketing.system.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.ticketing.system.Core.Application.dto.InventorySelectionDTO;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.Core.Application.services.SystemAdminService;
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
import com.ticketing.system.sales.domain.NoPurchasePolicy;
import com.ticketing.system.sales.adapter.out.persistence.SpringDataActiveOrderRepository;
import com.ticketing.system.catalog.adapter.out.persistence.SpringDataEventRepository;
import com.ticketing.system.organization.adapter.out.persistence.SpringDataProductionCompanyRepository;

/**
 * No-double-sell concurrency proof (V3-TX-03 / #361), run on the real JPA stack.
 *
 * <p>The contract tests cover reservation <em>behaviour</em> but not concurrency. This test fires N
 * buyers at a single unit of inventory at once and asserts <strong>exactly one wins</strong> with no
 * oversell — the guarantee that {@code @Version} optimistic locking is supposed to provide. It must
 * run on the {@code jpa} profile, because the in-memory repos serialise via real locks (a different
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
 * <p>Profiles {@code jpa,test}: JPA repositories on, Memory repos off, the in-process stub
 * payment/issuer adapters on (so the context wires), and H2 from {@code application-test.yml}. The
 * market gate is mocked open — it is not what this test proves.
 */
@SpringBootTest
@ActiveProfiles({"jpa", "test"})
class ReservationConcurrencyTest {

    private static final int CONCURRENT_BUYERS = 8;
    private static final int COMPANY_ID = 100;
    private static final String CONTESTED_SEAT = "A1";

    @Autowired
    private ReservationService reservationService;
    @Autowired
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
                new NoPurchasePolicy(), new DiscountPolicy(0));
        eventRepository.save(event);
    }

    @Test
    void givenManyGuestsReserveTheSameSeatConcurrently_thenExactlyOneWinsAndTheSeatIsNotOversold()
            throws InterruptedException {
        Outcome outcome = runConcurrentReserves("seat", seatedZoneId,
                InventorySelectionDTO.seated(List.of(CONTESTED_SEAT)));

        assertExactlyOneWinner(outcome);

        SeatedZone zone = (SeatedZone) reloadZone(SeatedZone.class);
        assertEquals(1, zone.getReservedAmount(), "the single seat is reserved exactly once");
        assertEquals(0, zone.getAvailableAmount(), "no seats remain available — no oversell");
    }

    @Test
    void givenManyGuestsReserveTheSameStandingPlaceConcurrently_thenExactlyOneWinsAndTheZoneIsNotOversold()
            throws InterruptedException {
        Outcome outcome = runConcurrentReserves("stand", standingZoneId, InventorySelectionDTO.standing(1));

        assertExactlyOneWinner(outcome);

        StandingZone zone = (StandingZone) reloadZone(StandingZone.class);
        assertEquals(1, zone.getReservedAmount(), "the single standing place is reserved exactly once");
        assertEquals(0, zone.getAvailableAmount(), "no capacity remains — no oversell");
    }

    /** Fires CONCURRENT_BUYERS guest reservations at the same unit simultaneously and tallies them. */
    private Outcome runConcurrentReserves(String buyerPrefix, int zoneId, InventorySelectionDTO selection)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_BUYERS);
        CountDownLatch armed = new CountDownLatch(CONCURRENT_BUYERS);
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(CONCURRENT_BUYERS);
        AtomicInteger successes = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < CONCURRENT_BUYERS; i++) {
            String guestSessionId = buyerPrefix + "-guest-" + i;
            pool.submit(() -> {
                try {
                    armed.countDown();
                    fire.await(); // release all workers at once to maximise the race window
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
        pool.shutdownNow();
        return new Outcome(successes.get(), failures);
    }

    private void assertExactlyOneWinner(Outcome outcome) {
        assertEquals(1, outcome.successes(),
                "exactly one buyer may win the single unit (got " + outcome.successes()
                        + " — more than one means an oversell)");
        assertEquals(CONCURRENT_BUYERS - 1, outcome.failures().size(),
                "every other buyer must fail cleanly; failures: " + outcome.failures());
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
