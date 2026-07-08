package com.ticketing.system.unit.infrastructure.persistence.EventPersistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.Core.Application.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.catalog.domain.DiscountPolicy;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.domain.StandingZone;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.sales.domain.NoPurchasePolicy;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.catalog.domain.VenueMap;
import com.ticketing.system.catalog.domain.EventCategory;

// Contract tests every EventRepository implementation must satisfy. Future JPA-backed
// adapter will subclass this with its own newRepository() factory; tests are reused.
public abstract class IEventRepositoryContractTest {

    protected abstract EventRepository newRepository();

    private EventRepository eventRepo;

    // Far-future timestamps used across test events (ShowDate enforces future-only
    // times).
    private static final LocalDateTime FUTURE_START = LocalDateTime.of(2099, 6, 1, 18, 0);
    private static final LocalDateTime FUTURE_END = LocalDateTime.of(2099, 6, 1, 22, 0);
    private static final Location LOCATION = new Location("Belgium", "Brussels");

    @BeforeEach
    void setUp() {
        eventRepo = newRepository();
    }

    // Builds a minimal valid Event with specified category and one zone priced at
    // 50.
    protected Event buildEvent(int id, String name, Double rating, int companyId, EventStatus status,
            EventCategory category) {
        VenueMap venueMap = new VenueMap(id, LOCATION, List.of(new StandingZone(1, "Floor", 100, 50)));
        ShowDate showDate = new ShowDate(FUTURE_START, FUTURE_END);
        return new Event(id, name, rating, List.of("Artist A"), category, companyId, status,
                venueMap, List.of(showDate), new NoPurchasePolicy(), new DiscountPolicy(0));
    }

    // An event whose zones span several prices (cheapest = 30); used for the "cheapest price" filter tests.
    protected Event buildMultiZoneEvent(int id) {
        VenueMap venueMap = new VenueMap(id, LOCATION, List.of(
                new StandingZone(1, "Cheap", 100, 30),
                new StandingZone(2, "Mid", 100, 50),
                new StandingZone(3, "Premium", 100, 100)));
        ShowDate showDate = new ShowDate(FUTURE_START, FUTURE_END);
        return new Event(id, "Tiered Show", 4.5, List.of("Artist A"), EventCategory.CONCERT, 10,
                EventStatus.ON_SALE, venueMap, List.of(showDate), new NoPurchasePolicy(), new DiscountPolicy(0));
    }

    // === save ===

    @Test
    void WhenSave_GivenValidEvent_returnsTheSavedEvent() {
        Event event = buildEvent(1, "Rock Night", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT);
        assertTrue(eventRepo.save(event));
    }

    // === findById ===

    @Test
    void givenSavedEvent_whenFindById_thenReturnsEvent() {
        eventRepo.save(buildEvent(1, "Rock Night", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));

        Event found = eventRepo.findById(1);

        assertNotNull(found);
        assertEquals(1, found.getId());
        assertEquals("Rock Night", found.getName());
    }

    // === findByCompanyId ===

    @Test
    void givenEventsForMultipleCompanies_whenFindByCompanyId_thenReturnsOnlyMatchingCompanyEvents() {
        eventRepo.save(buildEvent(1, "Event A", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));
        eventRepo.save(buildEvent(2, "Event B", 3.8, 10, EventStatus.DRAFT, EventCategory.CONCERT));
        eventRepo.save(buildEvent(3, "Event C", 4.2, 20, EventStatus.ON_SALE, EventCategory.CONCERT));

        List<Event> result = eventRepo.findByCompanyId(10);

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(e -> e.getCompanyId() == 10));
    }

    @Test
    void givenNoEventsForCompany_whenFindByCompanyId_thenReturnsEmptyList() {
        eventRepo.save(buildEvent(1, "Event A", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));

        List<Event> result = eventRepo.findByCompanyId(99);

        assertTrue(result.isEmpty());
    }

    // === findIdsByCompany ===

    @Test
    void givenEventsForCompany_whenFindIdsByCompany_thenReturnsOnlyIdsForThatCompany() {
        eventRepo.save(buildEvent(1, "Event A", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));
        eventRepo.save(buildEvent(2, "Event B", 3.8, 10, EventStatus.DRAFT, EventCategory.CONCERT));
        eventRepo.save(buildEvent(3, "Event C", 4.2, 20, EventStatus.ON_SALE, EventCategory.CONCERT));

        List<Integer> ids = eventRepo.findIdsByCompany(10);

        assertEquals(2, ids.size());
        assertTrue(ids.containsAll(List.of(1, 2)));
    }

    // === findActiveByCompany ===

    @Test
    void givenMixedStatusEventsForCompany_whenFindActiveByCompany_thenReturnsOnlyOnSaleEventsForThatCompany() {
        eventRepo.save(buildEvent(1, "On Sale", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));
        eventRepo.save(buildEvent(2, "Draft", 3.8, 10, EventStatus.DRAFT, EventCategory.CONCERT));
        eventRepo.save(buildEvent(3, "On Sale Other Company", 4.2, 20, EventStatus.ON_SALE, EventCategory.CONCERT));

        List<Event> result = eventRepo.findActiveByCompany(10);

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).getId());
    }

    // === findByStatus ===

    @Test
    void givenEventsWithDifferentStatuses_whenFindByStatus_thenReturnsOnlyMatchingStatusEvents() {
        eventRepo.save(buildEvent(1, "Event A", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));
        eventRepo.save(buildEvent(2, "Event B", 3.8, 10, EventStatus.DRAFT, EventCategory.CONCERT));
        eventRepo.save(buildEvent(3, "Event C", 4.2, 20, EventStatus.ON_SALE, EventCategory.CONCERT));

        List<Event> onSale = eventRepo.findByStatus(EventStatus.ON_SALE);
        List<Event> draft = eventRepo.findByStatus(EventStatus.DRAFT);

        assertEquals(2, onSale.size());
        assertEquals(1, draft.size());
        assertTrue(onSale.stream().allMatch(e -> e.getStatus() == EventStatus.ON_SALE));
    }

    @Test
    void givenNoEventsMatchingStatus_whenFindByStatus_thenReturnsEmptyList() {
        eventRepo.save(buildEvent(1, "Event A", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));

        List<Event> result = eventRepo.findByStatus(EventStatus.CANCELED);

        assertTrue(result.isEmpty());
    }

    // === findAll (#372 boot-time integrity scan) ===

    @Test
    void givenEventsAcrossCompaniesAndStatuses_whenFindAll_thenReturnsEveryEvent() {
        eventRepo.save(buildEvent(1, "Event A", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));
        eventRepo.save(buildEvent(2, "Event B", 3.8, 20, EventStatus.DRAFT, EventCategory.CONCERT));

        List<Event> all = eventRepo.findAll();

        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(e -> e.getId() == 1));
        assertTrue(all.stream().anyMatch(e -> e.getId() == 2));
    }

    @Test
    void givenNoEvents_whenFindAll_thenReturnsEmptyList() {
        assertTrue(eventRepo.findAll().isEmpty());
    }

    // === searchAll — eventName ===

    @Test
    void givenMatchingEventName_whensearchAll_thenReturnsMatchingEvent() {
        eventRepo.save(buildEvent(1, "Jazz Festival", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));
        eventRepo.save(buildEvent(2, "Rock Night", 3.8, 10, EventStatus.ON_SALE, EventCategory.CONCERT));

        List<Event> result = eventRepo.searchAll(new CatalogSearchFiltersDTO(
                "Jazz", null, null, null, null, null, null, null, null, null, null, null, null));

        assertEquals(1, result.size());
        assertEquals("Jazz Festival", result.get(0).getName());
    }

    @Test
    void givenNonMatchingEventName_whensearchAll_thenReturnsEmpty() {
        eventRepo.save(buildEvent(1, "Jazz Festival", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));

        List<Event> result = eventRepo.searchAll(new CatalogSearchFiltersDTO(
                "Opera", null, null, null, null, null, null, null, null, null, null, null, null));

        assertTrue(result.isEmpty());
    }

    // === searchAll — artistName ===

    @Test
    void givenMatchingArtistName_whensearchAll_thenReturnsMatchingEvent() {
        VenueMap vm = new VenueMap(1, LOCATION, List.of(new StandingZone(1, "Floor", 100, 50)));
        Event event = new Event(1, "Concert", 4.5, List.of("John Doe", "Jane Smith"),
                EventCategory.CONCERT, 10, EventStatus.ON_SALE, vm,
                List.of(new ShowDate(FUTURE_START, FUTURE_END)),
                new NoPurchasePolicy(), new DiscountPolicy(0));
        eventRepo.save(event);

        List<Event> result = eventRepo.searchAll(new CatalogSearchFiltersDTO(
                null, "John", null, null, null, null, null, null, null, null, null, null, null));

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).getId());
    }

    // === searchAll — category ===

    @Test
    void givenMatchingCategory_whensearchAll_thenReturnsMatchingEvent() {
        eventRepo.save(buildEvent(1, "Hamlet", 4.5, 10, EventStatus.ON_SALE, EventCategory.MUSIC)); // MUSIC
        VenueMap vm = new VenueMap(2, LOCATION, List.of(new StandingZone(1, "Stalls", 100, 60)));
        Event theaterEvent = new Event(2, "Hamlet2", 4.5, List.of("Actor B"),
                EventCategory.THEATER, 10, EventStatus.ON_SALE, vm,
                List.of(new ShowDate(FUTURE_START, FUTURE_END)),
                new NoPurchasePolicy(), new DiscountPolicy(0));
        eventRepo.save(theaterEvent);

        List<Event> result = eventRepo.searchAll(new CatalogSearchFiltersDTO(
                null, null, "MUSIC", null, null, null, null, null, null, null, null, null, null));

        assertEquals(1, result.size());
        assertEquals("Hamlet", result.get(0).getName());
    }

    @Test
    void givenUnknownCategory_whensearchAll_thenReturnsEmpty() {
        eventRepo.save(buildEvent(1, "Event A", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));

        List<Event> result = eventRepo.searchAll(new CatalogSearchFiltersDTO(
                null, null, "NOT_A_CATEGORY", null, null, null, null, null, null, null, null, null, null));

        assertTrue(result.isEmpty());
    }

    // === searchAll — keywords ===

    @Test
    void givenMatchingKeywordInEventName_whensearchAll_thenReturnsMatchingEvent() {
        eventRepo.save(buildEvent(1, "Summer Festival", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));
        eventRepo.save(buildEvent(2, "Rock Night", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));

        List<Event> result = eventRepo.searchAll(new CatalogSearchFiltersDTO(
                null, null, null, "Summer", null, null, null, null, null, null, null, null, null));

        assertEquals(1, result.size());
        assertEquals("Summer Festival", result.get(0).getName());
    }

    @Test
    void givenMatchingKeywordInArtistName_whensearchAll_thenReturnsMatchingEvent() {
        VenueMap vm = new VenueMap(1, LOCATION, List.of(new StandingZone(1, "Floor", 100, 50)));
        Event event = new Event(1, "Night Out", 4.5, List.of("Coldplay"),
                EventCategory.CONCERT, 10, EventStatus.ON_SALE, vm,
                List.of(new ShowDate(FUTURE_START, FUTURE_END)),
                new NoPurchasePolicy(), new DiscountPolicy(0));
        eventRepo.save(event);

        List<Event> result = eventRepo.searchAll(new CatalogSearchFiltersDTO(
                null, null, null, "Coldplay", null, null, null, null, null, null, null, null, null));

        assertEquals(1, result.size());
    }

    // === searchAll — date range ===

    @Test
    void givenEventWithShowDateInsideRange_whensearchAll_thenReturnsMatchingEvent() {
        eventRepo.save(buildEvent(1, "Future Show", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT)); // show date:
                                                                                                           // 2099-06-01

        List<Event> result = eventRepo.searchAll(new CatalogSearchFiltersDTO(
                null, null, null, null, null, null,
                LocalDate.of(2099, 5, 1), LocalDate.of(2099, 7, 1),
                null, null, null, null, null));

        assertEquals(1, result.size());
    }

    @Test
    void givenEventWithShowDateOutsideRange_whensearchAll_thenReturnsEmpty() {
        eventRepo.save(buildEvent(1, "Future Show", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT)); // show date:
                                                                                                           // 2099-06-01

        List<Event> result = eventRepo.searchAll(new CatalogSearchFiltersDTO(
                null, null, null, null, null, null,
                LocalDate.of(2100, 1, 1), LocalDate.of(2100, 12, 31),
                null, null, null, null, null));

        assertTrue(result.isEmpty());
    }

    // === searchAll — price range ===

    @Test
    void givenEventWithZoneInPriceRange_whensearchAll_thenReturnsMatchingEvent() {
        eventRepo.save(buildEvent(1, "Affordable Show", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT)); // zone
                                                                                                               // price
                                                                                                               // = 50

        List<Event> result = eventRepo.searchAll(new CatalogSearchFiltersDTO(
                null, null, null, null, 10.0, 100.0, null, null, null, null, null, null, null));

        assertEquals(1, result.size());
    }

    @Test
    void givenEventWithZonePriceBelowMinPrice_whensearchAll_thenReturnsEmpty() {
        eventRepo.save(buildEvent(1, "Cheap Show", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT)); // zone price
                                                                                                          // = 50

        List<Event> result = eventRepo.searchAll(new CatalogSearchFiltersDTO(
                null, null, null, null, 100.0, 500.0, null, null, null, null, null, null, null));

        assertTrue(result.isEmpty());
    }

    @Test
    void givenMultiZoneEvent_whenCheapestPriceInRange_thenMatches() {
        eventRepo.save(buildMultiZoneEvent(1)); // zones 30 / 50 / 100, cheapest = 30

        List<Event> result = eventRepo.searchAll(new CatalogSearchFiltersDTO(
                null, null, null, null, 20.0, 40.0, null, null, null, null, null, null, null));

        assertEquals(1, result.size()); // the cheapest ticket (30) is within [20, 40]
    }

    @Test
    void givenMultiZoneEvent_whenCheapestPriceBelowMin_thenExcludedEvenIfPricierZonesFit() {
        eventRepo.save(buildMultiZoneEvent(1)); // cheapest = 30

        List<Event> result = eventRepo.searchAll(new CatalogSearchFiltersDTO(
                null, null, null, null, 40.0, 200.0, null, null, null, null, null, null, null));

        // Matching is on the cheapest ticket (30), not the 50/100 zones, so the event is excluded.
        assertTrue(result.isEmpty());
    }

    // === searchAll — event rating range ===

    @Test
    void givenEventRatingAtOrAboveMin_whensearchAll_thenReturnsMatchingEvent() {
        eventRepo.save(buildEvent(1, "Top Rated", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));
        eventRepo.save(buildEvent(2, "Low Rated", 2.0, 10, EventStatus.ON_SALE, EventCategory.CONCERT));

        List<Event> result = eventRepo.searchAll(new CatalogSearchFiltersDTO(
                null, null, null, null, null, null, null, null, null, 4.0, null, null, null));

        assertEquals(1, result.size());
        assertEquals("Top Rated", result.get(0).getName());
    }

    @Test
    void givenEventRatingAboveMax_whensearchAll_thenReturnsEmpty() {
        eventRepo.save(buildEvent(1, "Top Rated", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));

        List<Event> result = eventRepo.searchAll(new CatalogSearchFiltersDTO(
                null, null, null, null, null, null, null, null, null, null, 4.0, null, null));

        assertTrue(result.isEmpty());
    }

    @Test
    void givenEventRatingWithinRange_whensearchAll_thenReturnsOnlyEventInBand() {
        eventRepo.save(buildEvent(1, "Mid Rated", 3.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));
        eventRepo.save(buildEvent(2, "Low Rated", 1.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));
        eventRepo.save(buildEvent(3, "Top Rated", 5.0, 10, EventStatus.ON_SALE, EventCategory.CONCERT));

        List<Event> result = eventRepo.searchAll(new CatalogSearchFiltersDTO(
                null, null, null, null, null, null, null, null, null, 3.0, 4.0, null, null));

        assertEquals(1, result.size());
        assertEquals("Mid Rated", result.get(0).getName());
    }

    // === searchAll — all-null filters ===

    @Test
    void givenAllNullFilters_whensearchAll_thenReturnsAllEvents() {
        eventRepo.save(buildEvent(1, "Event A", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));
        eventRepo.save(buildEvent(2, "Event B", 3.8, 10, EventStatus.DRAFT, EventCategory.CONCERT));

        List<Event> result = eventRepo.searchAll(new CatalogSearchFiltersDTO(
                null, null, null, null, null, null, null, null, null, null, null, null, null));

        assertEquals(2, result.size());
    }

    // === searchByCompanyAll — company scope (all statuses) + filters ===

    @Test
    void givenEventsForMultipleCompanies_whenSearchByCompanyAll_thenReturnsOnlyThatCompanyAllStatuses() {
        eventRepo.save(buildEvent(1, "Draft One", 4.5, 10, EventStatus.DRAFT, EventCategory.CONCERT));
        eventRepo.save(buildEvent(2, "Live One", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));
        eventRepo.save(buildEvent(3, "Other Co", 4.5, 20, EventStatus.ON_SALE, EventCategory.CONCERT));

        List<Event> result = eventRepo.searchByCompanyAll(10, CatalogSearchFiltersDTO.empty());

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(e -> e.getCompanyId() == 10));
    }

    @Test
    void givenCompanyEvents_whenSearchByCompanyAllWithNameFilter_thenFiltersWithinThatCompany() {
        eventRepo.save(buildEvent(1, "Jazz Night", 4.5, 10, EventStatus.DRAFT, EventCategory.CONCERT));
        eventRepo.save(buildEvent(2, "Rock Show", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));
        eventRepo.save(buildEvent(3, "Jazz Night", 4.5, 20, EventStatus.ON_SALE, EventCategory.CONCERT));

        List<Event> result = eventRepo.searchByCompanyAll(10, new CatalogSearchFiltersDTO(
                "Jazz", null, null, null, null, null, null, null, null, null, null, null, null));

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).getId());
    }
}
