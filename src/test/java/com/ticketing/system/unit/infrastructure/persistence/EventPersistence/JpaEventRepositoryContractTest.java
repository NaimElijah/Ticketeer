package com.ticketing.system.unit.infrastructure.persistence.EventPersistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.catalog.domain.DiscountPolicy;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.Seat;
import com.ticketing.system.catalog.domain.SeatedZone;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.catalog.domain.StandingZone;
import com.ticketing.system.catalog.domain.VenueMap;
import com.ticketing.system.shared.domain.policy.AgePurchasePolicy;
import com.ticketing.system.shared.domain.policy.AndPurchasePolicy;
import com.ticketing.system.shared.domain.policy.MaxTicketsPurchasePolicy;
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
@ActiveProfiles("jpa")
@Import(JpaEventRepository.class)
class JpaEventRepositoryContractTest extends IEventRepositoryContractTest {

    @Autowired
    private JpaEventRepository repository;

    @Autowired
    private SpringDataEventRepository data;

    @BeforeEach
    void cleanTable() {
        data.deleteAll();
    }

    @Override
    protected EventRepository newRepository() {
        return repository;
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
