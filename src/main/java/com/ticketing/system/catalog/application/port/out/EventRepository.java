package com.ticketing.system.catalog.application.port.out;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.Event;

import java.util.List;

import com.ticketing.system.shared.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.shared.IRepository;

/**
 * Driven port of the {@link Event} aggregate root: its loading, saving, searching and id minting, with
 * the write lock {@link IRepository} adds and the buyer-operation lock below. The Jpa and in-memory
 * adapters differ in locking; see {@link #lockForBuyerOperation} and {@link #findForSale}.
 */
public interface EventRepository extends IRepository<Event, Integer> {

    /**
     * Loads the event with the given id. Never returns {@code null}.
     *
     * @param eventId the event's id
     * @return the event
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
     * in-memory adapter relies on the buyer and structural locks instead. The Jpa adapter requires an
     * active transaction.
     *
     * @param eventId the event's id
     * @return the event
     * @throws com.ticketing.system.shared.exception.EventNotFoundException if there is no such event
     */
    Event findForSale(int eventId);

    /**
     * Stores an event, inserting a new one or updating a known one. The in-memory adapter requires the
     * caller to hold the event's write or buyer lock when the event already exists, and refuses with an
     * {@code IllegalStateException} otherwise.
     *
     * @param event the event to store
     * @return {@code true}
     */
    boolean save(Event event);

    /**
     * Shared lifecycle lock for buyer inventory operations.
     *
     * Many buyers may hold this lock for the same event at the same time.
     * On the in-memory adapter it blocks structural/event-lifecycle writes such as venue editing,
     * policy changes, cancellation, publishing, etc. On the Jpa adapter it is a no-op.
     *
     * Actual inventory correctness is still protected by the zone locks on the in-memory adapter, and
     * by the zone's and the seat's {@code @Version} on the Jpa adapter.
     *
     * @param eventId the event
     */
    void lockForBuyerOperation(int eventId);

    /**
     * Releases a buyer lifecycle lock acquired by lockForBuyerOperation.
     *
     * @param eventId the event
     */
    void unlockBuyerOperation(int eventId);

    /**
     * UC-3 / UC-22 / UC-31: loads every event of a company, in any status. This is a by-id
     * cross-aggregate query.
     *
     * @param companyId the owning company
     * @return the company's events; empty if it has none
     */
    List<Event> findByCompanyId(int companyId);

    /**
     * UC-3 / UC-22: lists the ids of a company's events, in any status, without loading the aggregates.
     *
     * @param companyId the owning company
     * @return the event ids; empty if the company has none
     */
    List<Integer> findIdsByCompany(int companyId);

    /**
     * UC-3 / UC-7: loads a company's ON_SALE events, for the public catalog. Whether the company is
     * active is the caller's check.
     *
     * @param companyId the owning company
     * @return the company's ON_SALE events; empty if it has none
     */
    List<Event> findActiveByCompany(int companyId);

    /**
     * UC-3 / UC-19: loads the events in one lifecycle status.
     *
     * @param status the status
     * @return the events in that status, whichever company owns them; empty if there are none
     */
    List<Event> findByStatus(EventStatus status);

    /**
     * upstream#372: loads every event, for the integrity scan, which re-checks each loaded event's invariants
     * and, in {@code SystemIntegrityVerifier.verifyInventoryWithinCapacity}, that no zone has more sold
     * and reserved places than its capacity.
     *
     * @return all events, in no particular order
     */
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