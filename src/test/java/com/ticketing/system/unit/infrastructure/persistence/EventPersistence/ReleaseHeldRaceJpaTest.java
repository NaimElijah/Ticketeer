package com.ticketing.system.unit.infrastructure.persistence.EventPersistence;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.catalog.adapter.out.persistence.JpaEventRepository;
import com.ticketing.system.catalog.adapter.out.persistence.SpringDataEventRepository;
import com.ticketing.system.catalog.application.port.in.InventoryCommandPort;
import com.ticketing.system.catalog.application.port.in.InventoryLineDTO;
import com.ticketing.system.catalog.application.service.InventoryService;
import com.ticketing.system.catalog.domain.DiscountPolicy;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.InventorySelection;
import com.ticketing.system.catalog.domain.InventoryZone;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.catalog.domain.StandingZone;
import com.ticketing.system.catalog.domain.VenueMap;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.shared.domain.policy.NoPurchasePolicy;

/**
 * A checkout rollback releases its holds even when a reservation in the same zone commits while the
 * release is under way (#187). The rollback runs outside any transaction ({@code CheckoutService}), so
 * the event it loads is detached by the time it is saved; a reservation committing in between bumps the
 * zone's {@code @Version}, and the stale save loses. That attempt committed nothing, so it is safe to run
 * again; letting it escape stops the rollback and leaves the cart's holds until they expire.
 *
 * <p>The interleaving is driven, not raced, by {@link RefundStockReturnRaceJpaTest}'s
 * {@code RacingEventRepository}: it commits one rival reservation in zone 1, in a {@code REQUIRES_NEW}
 * transaction, right after each load it is told to race. Runs the real {@link JpaEventRepository} and
 * {@link InventoryService} on H2, outside any test transaction, as the rollback runs.
 */
@DataJpaTest
@Import({JpaEventRepository.class, InventoryService.class, RefundStockReturnRaceJpaTest.RacingRepositoryConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReleaseHeldRaceJpaTest {

    private static final int EVENT_ID = 7302;
    private static final int COMPANY_ID = 10;
    // The zone RacingEventRepository's rival reserves in.
    private static final int ZONE_ID = 1;
    private static final int CAPACITY = 1_000;
    private static final int HELD_PLACES = 2;
    private static final String ROLLED_BACK_ORDER = "rolled-back-order";
    private static final LocalDateTime SHOW_START = LocalDateTime.of(2099, 6, 1, 18, 0);
    private static final LocalDateTime HOLD_UNTIL = LocalDateTime.of(2099, 1, 1, 0, 0);

    @Autowired private InventoryCommandPort inventory;
    @Autowired private JpaEventRepository events;
    @Autowired private SpringDataEventRepository data;
    @Autowired private RefundStockReturnRaceJpaTest.RacingEventRepository racing;

    // Not read by the release; InventoryService needs it to be built.
    @MockitoBean private ProductionCompanyRepository companies;

    @BeforeEach
    void storeAnEventWithTwoHeldPlaces() {
        data.deleteAll();
        racing.raceNextLoads(0);
        Event event = new Event(EVENT_ID, "Rollback Race", 4.0, List.of("Artist A"), EventCategory.CONCERT,
                COMPANY_ID, EventStatus.ON_SALE,
                new VenueMap(EVENT_ID, new Location("Belgium", "Brussels"),
                        List.of(new StandingZone(ZONE_ID, "Floor", CAPACITY, 50))),
                List.of(new ShowDate(SHOW_START, SHOW_START.plusHours(3))), ZoneId.of("Europe/Brussels"),
                new NoPurchasePolicy(), new DiscountPolicy(0));
        event.reserveInventory(ZONE_ID, InventorySelection.standing(HELD_PLACES, ROLLED_BACK_ORDER, HOLD_UNTIL));
        events.save(event);
    }

    @AfterEach
    void cleanUp() {
        racing.raceNextLoads(0);
        data.deleteAll();
    }

    @Test
    void GivenAReservationCommittingInTheSameZoneDuringTheRelease_WhenACheckoutRollbackReleasesItsHolds_ThenTheyAreReleased() {
        racing.raceNextLoads(1);

        inventory.releaseHeld(heldPlaces(), ROLLED_BACK_ORDER);

        InventoryZone zone = storedZone();
        assertAll(
                () -> assertEquals(1, racing.rivalsCommitted(), "arrangement: one rival reservation committed"),
                () -> assertEquals(1, zone.getReservedAmount(),
                        "only the rival's place should still be held, but " + zone.getReservedAmount() + " are"));
    }

    private static List<InventoryLineDTO> heldPlaces() {
        return List.of(new InventoryLineDTO(EVENT_ID, ZONE_ID, null), new InventoryLineDTO(EVENT_ID, ZONE_ID, null));
    }

    private InventoryZone storedZone() {
        return events.findById(EVENT_ID).getVenueMap().getZone(ZONE_ID);
    }
}
