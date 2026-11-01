package com.ticketing.system.unit.infrastructure.persistence.EventPersistence;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ch.qos.logback.classic.Level;

import com.ticketing.system.catalog.adapter.out.persistence.JpaEventRepository;
import com.ticketing.system.catalog.adapter.out.persistence.SpringDataEventRepository;
import com.ticketing.system.catalog.application.port.in.InventoryCommandPort;
import com.ticketing.system.catalog.application.port.in.InventoryLineDTO;
import com.ticketing.system.catalog.application.port.out.EventRepository;
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
import com.ticketing.system.shared.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.testutil.LogCapture;

/**
 * A member refund returns its standing places to stock even when a reservation in the same zone
 * commits while the return is under way (#187). The refund path runs without a transaction of its own
 * ({@code RefundService.requestRefund}), so the event it loads is detached by the time it is saved; a
 * reservation committing in between bumps the zone's {@code @Version}, and the stale save loses. That
 * attempt committed nothing, so it is safe to run again; dropping it leaves the places SOLD for good.
 *
 * <p>The interleaving is driven, not raced: {@link RacingEventRepository} commits one rival reservation
 * in a {@code REQUIRES_NEW} transaction right after each load it is told to race. Runs the real
 * {@link JpaEventRepository} and {@link InventoryService} on H2, outside any test transaction, as the
 * refund path runs.
 */
@DataJpaTest
@Import({JpaEventRepository.class, InventoryService.class, RefundStockReturnRaceJpaTest.RacingRepositoryConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefundStockReturnRaceJpaTest {

    private static final int EVENT_ID = 7301;
    private static final int COMPANY_ID = 10;
    private static final int ZONE_ID = 1;
    // Far more places than any number of rival reservations can take, so the event never sells out.
    private static final int CAPACITY = 1_000;
    private static final int REFUNDED_PLACES = 2;
    // More rivals than any bounded retry makes attempts, so every attempt of the stock return loses.
    private static final int RIVALS_FOR_EVERY_ATTEMPT = 100;
    private static final String BUYER_ORDER = "refunded-order";
    private static final LocalDateTime SHOW_START = LocalDateTime.of(2099, 6, 1, 18, 0);
    private static final LocalDateTime HOLD_UNTIL = LocalDateTime.of(2099, 1, 1, 0, 0);

    @Autowired private InventoryCommandPort inventory;
    @Autowired private JpaEventRepository events;
    @Autowired private SpringDataEventRepository data;
    @Autowired private RacingEventRepository racing;

    // Not read by the stock return; InventoryService needs it to be built.
    @MockitoBean private ProductionCompanyRepository companies;

    private LogCapture inventoryLog;

    @BeforeEach
    void storeAnEventWithTwoSoldPlaces() {
        data.deleteAll();
        racing.raceNextLoads(0);
        Event event = new Event(EVENT_ID, "Refund Race", 4.0, List.of("Artist A"), EventCategory.CONCERT,
                COMPANY_ID, EventStatus.ON_SALE,
                new VenueMap(EVENT_ID, new Location("Belgium", "Brussels"),
                        List.of(new StandingZone(ZONE_ID, "Floor", CAPACITY, 50))),
                List.of(new ShowDate(SHOW_START, SHOW_START.plusHours(3))), ZoneId.of("Europe/Brussels"),
                new NoPurchasePolicy(), new DiscountPolicy(0));
        event.reserveInventory(ZONE_ID, InventorySelection.standing(REFUNDED_PLACES, BUYER_ORDER, HOLD_UNTIL));
        event.confirmInventorySale(ZONE_ID, InventorySelection.standing(REFUNDED_PLACES, BUYER_ORDER));
        events.save(event);
        inventoryLog = LogCapture.of(InventoryService.class);
    }

    @AfterEach
    void cleanUp() {
        inventoryLog.close();
        racing.raceNextLoads(0);
        data.deleteAll();
    }

    @Test
    void GivenAReservationCommittingInTheSameZoneDuringTheStockReturn_WhenARefundReturnsItsPlaces_ThenTheyAreBackInStock() {
        racing.raceNextLoads(1);

        inventory.returnSoldToStock(refundedPlaces());

        InventoryZone zone = storedZone();
        assertAll(
                () -> assertEquals(1, racing.rivalsCommitted(), "arrangement: one rival reservation committed"),
                () -> assertEquals(0, zone.getSoldAmount(),
                        "the " + REFUNDED_PLACES + " refunded places should be back in stock, but "
                                + zone.getSoldAmount() + " are still SOLD"),
                () -> assertEquals(1, zone.getReservedAmount(), "the rival's reservation should be kept"));
    }

    @Test
    void GivenAReservationCommittingDuringEveryAttempt_WhenARefundReturnsItsPlaces_ThenAnErrorNamesTheEvent() {
        racing.raceNextLoads(RIVALS_FOR_EVERY_ATTEMPT);

        inventory.returnSoldToStock(refundedPlaces());

        List<String> errors = inventoryLog.messagesAt(Level.ERROR);
        assertAll(
                () -> assertTrue(errors.stream().anyMatch(message -> message.contains(String.valueOf(EVENT_ID))),
                        "a stock return that lost every attempt should be logged at ERROR naming event " + EVENT_ID
                                + "; ERROR lines: " + errors + "; everything mentioning it: "
                                + inventoryLog.eventsContaining(String.valueOf(EVENT_ID))),
                // One rival per attempt: the return runs three times in all, then gives up.
                () -> assertEquals(3, racing.rivalsCommitted(), "attempts made before giving up"),
                () -> assertEquals(REFUNDED_PLACES, storedZone().getSoldAmount(),
                        "a stock return that gave up should leave the refunded places SOLD"));
    }

    private static List<InventoryLineDTO> refundedPlaces() {
        return List.of(new InventoryLineDTO(EVENT_ID, ZONE_ID, null), new InventoryLineDTO(EVENT_ID, ZONE_ID, null));
    }

    private InventoryZone storedZone() {
        return events.findById(EVENT_ID).getVenueMap().getZone(ZONE_ID);
    }

    @TestConfiguration
    static class RacingRepositoryConfig {

        @Bean
        @Primary
        RacingEventRepository racingEventRepository(JpaEventRepository delegate,
                PlatformTransactionManager transactionManager) {
            return new RacingEventRepository(delegate, transactionManager);
        }
    }

    /**
     * The real Jpa adapter, except that each of the next {@code n} loads by id is followed, before it
     * returns, by a rival buyer's one-place reservation in the same zone, committed in a transaction of
     * its own. The rival loads and saves through the delegate, so it never races itself.
     */
    static final class RacingEventRepository implements EventRepository {

        private final JpaEventRepository delegate;
        private final TransactionTemplate rivalTransaction;
        private final AtomicInteger loadsToRace = new AtomicInteger();
        private final AtomicInteger rivalsCommitted = new AtomicInteger();

        RacingEventRepository(JpaEventRepository delegate, PlatformTransactionManager transactionManager) {
            this.delegate = delegate;
            this.rivalTransaction = new TransactionTemplate(transactionManager);
            this.rivalTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }

        void raceNextLoads(int loads) {
            loadsToRace.set(loads);
            rivalsCommitted.set(0);
        }

        int rivalsCommitted() {
            return rivalsCommitted.get();
        }

        @Override
        public Event findById(int eventId) {
            Event loaded = delegate.findById(eventId);
            if (loadsToRace.get() > 0) {
                loadsToRace.decrementAndGet();
                commitRivalReservation(eventId);
            }
            return loaded;
        }

        private void commitRivalReservation(int eventId) {
            rivalTransaction.executeWithoutResult(status -> {
                Event current = delegate.findById(eventId);
                current.reserveInventory(ZONE_ID, InventorySelection.standing(
                        1, "rival-order-" + (rivalsCommitted.get() + 1), HOLD_UNTIL));
                delegate.save(current);
            });
            rivalsCommitted.incrementAndGet();
        }

        @Override
        public Event findForSale(int eventId) {
            return delegate.findForSale(eventId);
        }

        @Override
        public boolean save(Event event) {
            return delegate.save(event);
        }

        @Override
        public void lockForBuyerOperation(int eventId) {
            delegate.lockForBuyerOperation(eventId);
        }

        @Override
        public void unlockBuyerOperation(int eventId) {
            delegate.unlockBuyerOperation(eventId);
        }

        @Override
        public void lockForUpdate(Integer id) {
            delegate.lockForUpdate(id);
        }

        @Override
        public void unlock(Integer id) {
            delegate.unlock(id);
        }

        @Override
        public List<Event> findByCompanyId(int companyId) {
            return delegate.findByCompanyId(companyId);
        }

        @Override
        public List<Integer> findIdsByCompany(int companyId) {
            return delegate.findIdsByCompany(companyId);
        }

        @Override
        public List<Event> findActiveByCompany(int companyId) {
            return delegate.findActiveByCompany(companyId);
        }

        @Override
        public List<Event> findByStatus(EventStatus status) {
            return delegate.findByStatus(status);
        }

        @Override
        public List<Event> findAll() {
            return delegate.findAll();
        }

        @Override
        public List<Event> searchAll(CatalogSearchFiltersDTO filters) {
            return delegate.searchAll(filters);
        }

        @Override
        public List<Event> searchONSALE(CatalogSearchFiltersDTO filters) {
            return delegate.searchONSALE(filters);
        }

        @Override
        public List<Event> searchByCompanyAll(int companyId, CatalogSearchFiltersDTO filters) {
            return delegate.searchByCompanyAll(companyId, filters);
        }

        @Override
        public void delete(int eventId) {
            delegate.delete(eventId);
        }

        @Override
        public int nextId() {
            return delegate.nextId();
        }

        @Override
        public int nextVenueMapId() {
            return delegate.nextVenueMapId();
        }
    }
}
