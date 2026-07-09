package com.ticketing.system.catalog.adapter.out.persistence;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.shared.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.shared.exception.EventNotFoundException;

/**
 * JPA-backed {@link EventRepository} — active only in the {@code jpa} run/dev profile. Adapts the
 * domain port onto Spring Data ({@link SpringDataEventRepository}); the application layer depends only
 * on {@code EventRepository}, never on Spring Data. The owned VenueMap / zone hierarchy / seats /
 * element collections persist by cascade with the event.
 *
 * <p>Locking is OPTIMISTIC and fine-grained: {@code lockForUpdate}/{@code lockForBuyerOperation} and
 * their unlocks are no-ops. Event carries an {@code @Version} for structural/lifecycle edits, while
 * Seat and InventoryZone carry their own {@code @Version} so two buyers reserving different seats (or
 * a buyer vs. a structural edit touching a different entity) never false-conflict; genuine conflicts
 * raise {@code OptimisticLockException}, retried by the service {@code @Retryable} (C1, #359).
 *
 * <p>{@code searchAll}/{@code searchONSALE} push the 18-filter catalogue search down into a Criteria
 * {@link Specification} (see {@link EventSearchSpecification}). {@code nextId}/{@code nextVenueMapId}
 * seed in-memory counters from the current max ids so they survive a restart.
 */
@Repository
@Profile("jpa")
public class JpaEventRepository implements EventRepository {

    private final SpringDataEventRepository data;
    private final AtomicInteger eventIdSequence = new AtomicInteger(0);
    private final AtomicInteger venueMapIdSequence = new AtomicInteger(0);
    private volatile boolean seeded = false;

    public JpaEventRepository(SpringDataEventRepository data) {
        this.data = data;
    }

    @Override
    public void lockForUpdate(Integer id) { /* no-op — fine-grained @Version optimistic locking */ }

    @Override
    public void unlock(Integer id) { /* no-op */ }

    @Override
    public void lockForBuyerOperation(int eventId) { /* no-op */ }

    @Override
    public void unlockBuyerOperation(int eventId) { /* no-op */ }

    @Override
    public int nextId() {
        ensureSeeded();
        return eventIdSequence.incrementAndGet();
    }

    @Override
    public int nextVenueMapId() {
        ensureSeeded();
        return venueMapIdSequence.incrementAndGet();
    }

    private void ensureSeeded() {
        if (!seeded) {
            synchronized (this) {
                if (!seeded) {
                    eventIdSequence.set(data.findMaxEventId());
                    venueMapIdSequence.set(data.findMaxVenueMapId());
                    seeded = true;
                }
            }
        }
    }

    @Override
    public Event findById(int eventId) {
        return data.findById(eventId)
                .orElseThrow(() -> new EventNotFoundException("Event with ID " + eventId + " not found"));
    }

    @Override
    @Transactional
    public boolean save(Event event) {
        data.save(event);
        return true;
    }

    @Override
    @Transactional
    public void delete(int eventId) {
        data.deleteById(eventId);
    }

    @Override
    public List<Event> findByCompanyId(int companyId) {
        return data.findByCompanyId(companyId);
    }

    @Override
    public List<Integer> findIdsByCompany(int companyId) {
        return data.findIdsByCompany(companyId);
    }

    @Override
    public List<Event> findActiveByCompany(int companyId) {
        return data.findActiveByCompany(companyId);
    }

    @Override
    public List<Event> findByStatus(EventStatus status) {
        return data.findByStatus(status);
    }

    @Override
    public List<Event> findAll() {
        return data.findAll();
    }

    @Override
    public List<Event> searchAll(CatalogSearchFiltersDTO filters) {
        return data.findAll(EventSearchSpecification.from(filters));
    }

    @Override
    public List<Event> searchONSALE(CatalogSearchFiltersDTO filters) {
        Specification<Event> spec = EventSearchSpecification.from(filters)
                .and((root, query, cb) -> cb.equal(root.get("status"), EventStatus.ON_SALE));
        return data.findAll(spec);
    }

    @Override
    public List<Event> searchByCompanyAll(int companyId, CatalogSearchFiltersDTO filters) {
        // "comapnyid" is the Event field name (a domain typo) — see SpringDataEventRepository.
        Specification<Event> spec = EventSearchSpecification.from(filters)
                .and((root, query, cb) -> cb.equal(root.get("comapnyid"), companyId));
        return data.findAll(spec);
    }
}
