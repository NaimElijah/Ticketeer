package com.ticketing.system.unit.infrastructure.persistence.EventPersistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.function.Supplier;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Test
    void save_roundTripsFullVenueWithSeatsAndPolicies() {
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
