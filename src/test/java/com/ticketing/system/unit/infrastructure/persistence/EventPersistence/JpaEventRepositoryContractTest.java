package com.ticketing.system.unit.infrastructure.persistence.EventPersistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.function.Supplier;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

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
import com.ticketing.system.shared.domain.policy.AgePurchasePolicy;
import com.ticketing.system.shared.domain.policy.AndPurchasePolicy;
import com.ticketing.system.shared.domain.policy.MaxTicketsPurchasePolicy;
import com.ticketing.system.shared.domain.policy.NoPurchasePolicy;
import com.ticketing.system.shared.domain.policy.OrPurchasePolicy;
import com.ticketing.system.shared.domain.policy.PurchasePolicy;
import com.ticketing.system.shared.domain.policy.PurchasePolicyJsonConverter;
import com.ticketing.system.catalog.adapter.out.persistence.JpaEventRepository;
import com.ticketing.system.catalog.adapter.out.persistence.SpringDataEventRepository;

/**
 * Runs the {@link IEventRepositoryContractTest} suite (incl. all search filters) against the JPA
 * adapter on an embedded H2 schema, and adds the acceptance round-trip: a full venue with a seated
 * zone (seats), a standing zone, and policies survives save/reload. {@code @DataJpaTest} builds the
 * whole graph of tables (events, venue_maps, inventory_zones + seated/standing subtables, seats,
 * element collections). Each test starts from an empty schema; CI never touches a real database.
 */
@DataJpaTest
@Import(JpaEventRepository.class)
class JpaEventRepositoryContractTest extends IEventRepositoryContractTest {

    @Autowired
    private JpaEventRepository repository;

    @Autowired
    private SpringDataEventRepository data;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void cleanTable() {
        data.deleteAll();
    }

    @Override
    protected EventRepository newRepository() {
        return repository;
    }

    // These inherited tests run without a test transaction, and findForSale's lock needs the caller's.
    @Override
    protected <T> T inSaleTransaction(Supplier<T> call) {
        return new TransactionTemplate(transactionManager).execute(status -> call.get());
    }

    // The shared lock lasts as long as its transaction, so one opened for this call alone would end before
    // the sale it protects; the adapter refuses to run without the caller's (ADR-015).
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void GivenNoTransaction_WhenAnEventIsLoadedForSale_ThenItIsRefused() {
        repository.save(buildEventInZone(1, ZoneId.of("Asia/Jerusalem")));

        assertThrows(IllegalTransactionStateException.class, () -> repository.findForSale(1));
    }

    // VenueMap's constructor refuses two zones named alike, but checkInvariants does not, on purpose: a map
    // stored before #187 with "Cheap" and "CHEAP" must still pass its own check. Event.checkInvariants does
    // not run that check yet; once it does, SystemIntegrityVerifier runs it on every stored event when the
    // market opens. The second name is written in SQL, as such a row was, and read back through the adapter.
    @Test
    void GivenAStoredMapWhoseZonesAreNamedAlike_WhenLoaded_ThenItsInvariantsPass() {
        repository.save(buildMultiZoneEvent(1));
        entityManager.flush();
        entityManager.getEntityManager()
                .createNativeQuery("update inventory_zones set name = 'CHEAP' where name = 'Mid'")
                .executeUpdate();
        entityManager.clear();

        Event loaded = repository.findById(1);

        assertEquals(List.of("CHEAP", "Cheap", "Premium"), loaded.getVenueMap().getInventoryZones().stream()
                .map(InventoryZone::getName).sorted().toList(),
                "arrangement: the stored map holds two zones named alike");
        assertDoesNotThrow(loaded.getVenueMap()::checkInvariants);
    }

    // Flush and clear so the zone is read back from events.time_zone, not the persistence context.
    @Test
    void GivenEventInLondon_WhenFlushedAndReloaded_ThenTimeZoneComesBackFromTheColumn() {
        repository.save(buildEventInZone(1, ZoneId.of("Europe/London")));
        entityManager.flush();
        entityManager.clear();

        assertEquals(ZoneId.of("Europe/London"), repository.findById(1).getTimeZone());
    }

    // Hibernate writes the policy column only when the new policy differs from the loaded one by equals,
    // so a change that equals missed would be dropped without an error. AND to OR over the same rules is
    // the closest pair.
    @Test
    void GivenAStoredPurchasePolicy_WhenReplacedByOneOverTheSameRules_ThenTheReloadedEventHasTheNewOne() {
        PurchasePolicy all = new AndPurchasePolicy(new AgePurchasePolicy(18), new MaxTicketsPurchasePolicy(4));
        PurchasePolicy either = new OrPurchasePolicy(new AgePurchasePolicy(18), new MaxTicketsPurchasePolicy(4));
        Event event = buildEventInZone(1, ZoneId.of("Asia/Jerusalem"));
        event.setPurchasePolicy(all);
        repository.save(event);
        entityManager.flush();
        entityManager.clear();

        Event loaded = repository.findById(1);
        loaded.setPurchasePolicy(either);
        repository.save(loaded);
        entityManager.flush();
        entityManager.clear();

        // Compared as JSON, not by equals, which is what this test doubts.
        PurchasePolicyJsonConverter converter = new PurchasePolicyJsonConverter();
        assertEquals(converter.convertToDatabaseColumn(either),
                converter.convertToDatabaseColumn(repository.findById(1).getPurchasePolicy()));
    }

    // A new adapter instance models a restarted process (#19, as JpaTicketRepositoryContractTest does for
    // tickets): both id sequences must resume above the rows a previous boot persisted, not start at 1.
    @Test
    void GivenEventsPersistedByAPreviousBoot_WhenAFreshAdapterMintsIds_ThenEachIsAboveItsExistingMax() {
        data.saveAllAndFlush(List.of(seatedEvent(1, 7), seatedEvent(2, 8), seatedEvent(3, 9)));

        JpaEventRepository afterRestart = new JpaEventRepository(data);

        int eventId = afterRestart.nextId();
        int venueMapId = afterRestart.nextVenueMapId();
        assertTrue(eventId > 3, "event id after a restart: " + eventId);
        assertTrue(venueMapId > 9, "venue map id after a restart: " + venueMapId);
    }

    // The Jpa adapter needs no lock to save a stored event again: @Version guards the write, and the
    // Memory adapter, which has none, refuses (MemoryEventRepositoryTest). Outside the test transaction
    // the load is a detached copy, so the change reaches the row only through this save.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void GivenAStoredEvent_WhenLoadedChangedAndSavedWithoutALock_ThenTheChangeIsStored() {
        repository.save(buildEvent(1, "Rock Night", 4.5, 10, EventStatus.SCHEDULED, EventCategory.CONCERT));
        Event loaded = repository.findById(1);
        loaded.addPlacesToStandingZone(1, 20, 10);
        loaded.transitionToOnSale();

        repository.save(loaded);

        Event reloaded = repository.findById(1);
        assertEquals(EventStatus.ON_SALE, reloaded.getStatus());
        assertEquals(List.of(120), standingCapacities(reloaded));
    }

    // The Jpa adapter needs no lock to delete (the Memory adapter refuses without one, MemoryEventRepositoryTest),
    // and the delete takes the event's whole owned graph with it. It runs outside the test transaction, so
    // each call commits and the counts read what the database holds.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void GivenTwoEventsWithSeatedVenues_WhenOneIsDeletedWithoutALock_ThenOnlyItsOwnedRowsAreGone() {
        repository.save(seatedEvent(1));
        repository.save(seatedEvent(2));

        repository.delete(1);

        assertEquals(Map.of("events", 1, "venue_maps", 1, "inventory_zones", 2, "seats", 2,
                        "event_show_dates", 1, "event_artists", 1),
                rowCounts(), "only event 2's rows stay");
        assertEquals(List.of(2), repository.findAll().stream().map(Event::getId).toList());
    }

    private Map<String, Integer> rowCounts() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return Map.of(
                "events", jdbc.queryForObject("select count(*) from events", Integer.class),
                "venue_maps", jdbc.queryForObject("select count(*) from venue_maps", Integer.class),
                "inventory_zones", jdbc.queryForObject("select count(*) from inventory_zones", Integer.class),
                "seats", jdbc.queryForObject("select count(*) from seats", Integer.class),
                "event_show_dates", jdbc.queryForObject("select count(*) from event_show_dates", Integer.class),
                "event_artists", jdbc.queryForObject("select count(*) from event_artists", Integer.class));
    }

    private static Event seatedEvent(int id) {
        return seatedEvent(id, id);
    }

    private static Event seatedEvent(int id, int venueMapId) {
        VenueMap venue = new VenueMap(venueMapId, new Location("Belgium", "Brussels"), List.of(
                new SeatedZone(1, "Stage", 80.0, List.of(new Seat("A1", 0, 0), new Seat("A2", 1, 0))),
                new StandingZone(2, "Floor", 100, 50.0)));
        return new Event(id, "Show " + id, 4.5, List.of("Artist A"), EventCategory.MUSIC, 7, EventStatus.CANCELED,
                venue, List.of(new ShowDate(LocalDateTime.of(2099, 6, 1, 18, 0), LocalDateTime.of(2099, 6, 1, 22, 0))),
                ZoneId.of("Asia/Jerusalem"), new NoPurchasePolicy(), new DiscountPolicy(0));
    }

    @Test
    void GivenAnEventWithAFullVenueSeatsAndPolicies_WhenSaveAndFindById_ThenReturnsFullVenueWithSeatsAndPolicies() {
        int eventId = repository.nextId();
        SeatedZone seated = new SeatedZone(1, "Stage", 80.0,
                List.of(new Seat("A1", 0, 0), new Seat("A2", 1, 0)));
        StandingZone standing = new StandingZone(2, "Floor", 100, 50.0);
        VenueMap venue = new VenueMap(repository.nextVenueMapId(), new Location("Belgium", "Brussels"),
                List.of(seated, standing));
        PurchasePolicy policy = new AndPurchasePolicy(new AgePurchasePolicy(18), new MaxTicketsPurchasePolicy(4));

        Event event = new Event(eventId, "Concert", 4.5, List.of("Coldplay"), EventCategory.MUSIC, 7,
                EventStatus.ON_SALE, venue, List.of(new ShowDate(
                        LocalDateTime.of(2099, 6, 1, 18, 0), LocalDateTime.of(2099, 6, 1, 22, 0))),
                ZoneId.of("Asia/Jerusalem"),
                policy, new DiscountPolicy(0));
        repository.save(event);
        // This test runs in the test transaction: without the flush and clear, findById would hand back
        // the instance built above from the persistence context, and no row would be read.
        entityManager.flush();
        entityManager.clear();

        Event found = repository.findById(eventId);
        assertEquals(2, found.getVenueMap().getInventoryZones().size());

        SeatedZone foundSeated = (SeatedZone) found.getVenueMap().getInventoryZones().stream()
                .filter(z -> z instanceof SeatedZone).findFirst().orElseThrow();
        assertEquals(2, foundSeated.getSeats().size());
        assertTrue(foundSeated.getSeats().stream().anyMatch(s -> "A1".equals(s.getLabel())));

        // the recursive purchase policy tree round-trips (compared via the converter)
        PurchasePolicyJsonConverter converter = new PurchasePolicyJsonConverter();
        assertEquals(converter.convertToDatabaseColumn(policy),
                converter.convertToDatabaseColumn(found.getPurchasePolicy()));
    }
}
