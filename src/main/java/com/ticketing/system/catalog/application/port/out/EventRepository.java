package com.ticketing.system.catalog.application.port.out;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.Event;

import java.util.List;

import com.ticketing.system.shared.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.shared.IRepository;

// Aggregate-root entry point for the Event aggregate.
public interface EventRepository extends IRepository<Event, Integer> {

    Event findById(int eventId);

    boolean save(Event event);

    /**
     * Shared lifecycle lock for buyer inventory operations.
     *
     * Many buyers may hold this lock for the same event at the same time.
     * It blocks structural/event-lifecycle writes such as venue editing, policy changes,
     * cancellation, publishing, etc.
     *
     * Actual inventory correctness is still protected by StandingZone / SeatedZone locks.
     */
    void lockForBuyerOperation(int eventId);

    /**
     * Releases a buyer lifecycle lock acquired by lockForBuyerOperation.
     */
    void unlockBuyerOperation(int eventId);

    // UC-3 / UC-22 / UC-31 — events of a given company (by-ID cross-aggregate query).
    List<Event> findByCompanyId(int companyId);

    // UC-3 / UC-22 — fast id-only projection (avoids loading full Event aggregates).
    List<Integer> findIdsByCompany(int companyId);

    // UC-3 / UC-7 — public catalog uses ON_SALE + active company filter.
    List<Event> findActiveByCompany(int companyId);

    // UC-3 / UC-19 — events grouped by lifecycle state.
    List<Event> findByStatus(EventStatus status);

    // #372 — full enumeration for the boot-time integrity scan (sellable <= inventory).
    List<Event> findAll();

    // search across all events (filters from DTO).
    List<Event> searchAll(CatalogSearchFiltersDTO filters);
    // UC-7 — search across all events that are ON_SALE (filters from DTO).
    List<Event> searchONSALE(CatalogSearchFiltersDTO filters);
    // Owner event list — a single company's events (all statuses), narrowed by the DTO filters.
    List<Event> searchByCompanyAll(int companyId, CatalogSearchFiltersDTO filters);
    

    // Permanently removes a CANCELED event from the store.
    void delete(int eventId);

    int nextId();

    int nextVenueMapId();
}