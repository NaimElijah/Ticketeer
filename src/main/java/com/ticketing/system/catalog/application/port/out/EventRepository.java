package com.ticketing.system.catalog.application.port.out;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.Event;

import java.util.List;

import com.ticketing.system.shared.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.shared.IRepository;

// Aggregate-root entry point for the Event aggregate.
public interface EventRepository extends IRepository<Event, Integer> {

    /**
     * Loads the event with the given id. Never returns {@code null}.
     *
     * @throws com.ticketing.system.shared.exception.EventNotFoundException if there is no such event
     */
    Event findById(int eventId);

    /**
     * Loads the event for a sale that commits in the caller's transaction. On a database, a row lock is
     * held until that transaction ends: on PostgreSQL a shared one, which other sales may hold at the same
     * time; on H2, which has none, an exclusive one. A write to the event row, such as a cancel's
     * CANCELED, waits for the sale to commit. During a long run of overlapping sales it can keep waiting,
     * because a new shared lock does not queue behind it (ADR-015). The cancel's refund then
     * sees the sale's receipt, instead of the sale committing on a cancelled event with no refund. The
     * in-memory adapter relies on the buyer and structural locks instead.
     *
     * @throws com.ticketing.system.shared.exception.EventNotFoundException if there is no such event
     */
    Event findForSale(int eventId);

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

    /**
     * Searches every event, in any status.
     *
     * @param filters the filters to narrow by; {@code null} means no filter, as {@link CatalogSearchFiltersDTO#empty()}
     * @return the events matching every given filter, in no particular order
     */
    List<Event> searchAll(CatalogSearchFiltersDTO filters);

    /**
     * Searches the events that are {@code ON_SALE}: the buyer's catalogue search (UC-7).
     *
     * @param filters the filters to narrow by; {@code null} means no filter, as {@link CatalogSearchFiltersDTO#empty()}
     * @return the {@code ON_SALE} events matching every given filter, in no particular order
     */
    List<Event> searchONSALE(CatalogSearchFiltersDTO filters);

    /**
     * Searches one company's events, in any status: the owner's event list.
     *
     * @param companyId the company whose events to search
     * @param filters   the filters to narrow by; {@code null} means no filter, as {@link CatalogSearchFiltersDTO#empty()}
     * @return that company's events matching every given filter, in no particular order
     */
    List<Event> searchByCompanyAll(int companyId, CatalogSearchFiltersDTO filters);
    

    /**
     * Permanently removes a CANCELED event from the store, with everything it owns. An id the store does not
     * hold changes nothing.
     */
    void delete(int eventId);

    /**
     * Mints a new event id, greater than any this method returned before, a previous boot's included. So while
     * every event's id comes from here, a new event never reuses a stored one's.
     */
    int nextId();

    /** Mints a new venue map id, on the same terms as {@link #nextId()}. */
    int nextVenueMapId();
}