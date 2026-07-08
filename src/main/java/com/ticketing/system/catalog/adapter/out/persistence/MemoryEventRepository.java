package com.ticketing.system.catalog.adapter.out.persistence;

import com.ticketing.system.shared.persistence.RepositoryReadWriteLocks;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.ticketing.system.Core.Application.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.shared.exception.EventNotFoundException;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!jpa")
public class MemoryEventRepository implements EventRepository {

    private final ConcurrentHashMap<Integer, Event> events = new ConcurrentHashMap<>();
    private final AtomicInteger idSequence = new AtomicInteger(1);
    private final AtomicInteger venueMapIdSequence = new AtomicInteger(1);
    private final RepositoryReadWriteLocks<Integer> locks = new RepositoryReadWriteLocks<>();  // per-event locks for lifecycle synchronization

    @Override
    public void lockForUpdate(Integer id) {
        locks.lockWrite(id);
    }

    @Override
    public void unlock(Integer id) {
        locks.unlockWrite(id);
    }

    @Override
    public void lockForBuyerOperation(int eventId) {
        locks.lockRead(eventId);
    }

    @Override
    public void unlockBuyerOperation(int eventId) {
        locks.unlockRead(eventId);
    }

    @Override
    public int nextId() {
        return idSequence.getAndIncrement();
    }

    @Override
    public int nextVenueMapId() {
        return venueMapIdSequence.getAndIncrement();
    }

    @Override
    public Event findById(int eventId) {
        if (!events.containsKey(eventId)) {
            throw new EventNotFoundException("Event with ID " + eventId + " not found");
        }
        return events.get(eventId);
    }

    @Override
    public void delete(int eventId) {
        // Delete is a structural/lifecycle change: an existing event must be write-locked
        // by the calling thread, mirroring save(), so it can't race with buyer read locks
        // (lockForBuyerOperation) or other lifecycle writes.
        if (events.containsKey(eventId)
                && !locks.isWriteHeldByCurrentThread(eventId)) {
            throw new IllegalStateException("Event " + eventId + " must be locked before deleting");
        }
        events.remove(eventId);
    }

    @Override
    public boolean save(Event event) {
        // New events (not yet in the map) may be saved without a lock — no other
        // thread can know the ID before the first save completes.
        // Existing events must be locked by the calling thread to prevent
        // unguarded read-modify-write races.
        if (events.containsKey(event.getId())
                && !locks.isWriteHeldByCurrentThread(event.getId())
                && !locks.isReadHeldByCurrentThread(event.getId())) {
            throw new IllegalStateException("Event " + event.getId() + " must be locked before saving");
        }
        events.put(event.getId(), event);
        return true;
    }

    @Override
    public List<Event> findByCompanyId(int companyId) {
        return events.values().stream()
                .filter(e -> e.getCompanyId() == companyId)
                .collect(Collectors.toList());
    }

    @Override
    public List<Integer> findIdsByCompany(int companyId) {
        return events.values().stream()
                .filter(e -> e.getCompanyId() == companyId)
                .map(Event::getId)
                .collect(Collectors.toList());
    }

    @Override
    public List<Event> findActiveByCompany(int companyId) {
        return events.values().stream()
                .filter(e -> e.getCompanyId() == companyId && e.getStatus() == EventStatus.ON_SALE)
                .collect(Collectors.toList());
    }

    @Override
    public List<Event> findByStatus(EventStatus status) {
        return events.values().stream()
                .filter(e -> e.getStatus() == status)
                .collect(Collectors.toList());
    }

    @Override
    public List<Event> findAll() {
        return events.values().stream().collect(Collectors.toList());
    }






    @Override
    public List<Event> searchONSALE(CatalogSearchFiltersDTO filters) {
        // we'll just call the full search and then filter ON_SALE in-memory since this is an in-memory repo;
        // a real DB implementation would push the ON_SALE filter down into the query for efficiency.
        return searchAll(filters).stream()
                .filter(e -> e.getStatus() == EventStatus.ON_SALE)
                .collect(Collectors.toList());
    }


    // UC-7: Global search with multiple optional filters.
    @Override
    public List<Event> searchAll(CatalogSearchFiltersDTO filters) {
        return events.values().stream()
                .filter(e -> matchesSearch(e, filters))
                .collect(Collectors.toList());
    }

    // Owner event list: a single company's events (all statuses) narrowed by the same filters.
    @Override
    public List<Event> searchByCompanyAll(int companyId, CatalogSearchFiltersDTO filters) {
        return events.values().stream()
                .filter(e -> e.getCompanyId() == companyId)
                .filter(e -> matchesSearch(e, filters))
                .collect(Collectors.toList());
    }

    /*
     * A helper method to apply the various search filters to an event and return a boolean indicating
     * whether the event matches the filters; used in the search() implementation above.
     */
    private boolean matchesSearch(Event event, CatalogSearchFiltersDTO filters) {
        // eventName — case-insensitive substring match on event name.
        if (filters.eventName() != null &&
                !event.getName().toLowerCase().contains(filters.eventName().toLowerCase())) {
            return false;
        }

        // artistName — at least one artist must match (case-insensitive substring).
        if (filters.artistName() != null) {
            if (event.getArtistsNames() == null ||
                    !event.getArtistsNames().stream().anyMatch(
                            a -> a.toLowerCase().contains(filters.artistName().toLowerCase()))) {
                return false;
            }
        }

        // category — exact enum match by name (case-insensitive).
        if (filters.category() != null) {
            try {
                EventCategory filterCategory = EventCategory.valueOf(filters.category().toUpperCase());
                if (event.getCategory() != filterCategory) {
                    return false;
                }
            } catch (IllegalArgumentException e) {
                return false; // unknown category value — no event can match.
            }
        }

        // keywords — case-insensitive substring match on event name or any artist name.
        if (filters.keywords() != null) {
            String kw = filters.keywords().toLowerCase();
            boolean nameMatch = event.getName() != null && event.getName().toLowerCase().contains(kw);
            boolean artistMatch = event.getArtistsNames() != null &&
                    event.getArtistsNames().stream().anyMatch(a -> a.toLowerCase().contains(kw));
            if (!nameMatch && !artistMatch) {
                return false;
            }
        }

        // fromDate / toDate — at least one ShowDate must fall within the range.
        if (filters.fromDate() != null || filters.toDate() != null) {
            boolean hasMatchingDate = event.getShowDates() != null && event.getShowDates().stream().anyMatch(sd -> {
                LocalDate date = sd.getStartTime().toLocalDate();
                if (filters.fromDate() != null && date.isBefore(filters.fromDate()))
                    return false;
                if (filters.toDate() != null && date.isAfter(filters.toDate()))
                    return false;
                return true;
            });
            if (!hasMatchingDate)
                return false;
        }

        // minPrice / maxPrice — the event's CHEAPEST ticket price (its lowest zone price) must fall
        // within the range. An event with no venue/zones has no price and never matches a price filter.
        if (filters.minPrice() != null || filters.maxPrice() != null) {
            if (event.getVenueMap() == null || event.getVenueMap().getInventoryZones() == null
                    || event.getVenueMap().getInventoryZones().isEmpty())
                return false;
            double cheapest = event.getVenueMap().getInventoryZones().stream()
                    .mapToDouble(z -> z.getprice()).min().getAsDouble();
            if (filters.minPrice() != null && cheapest < filters.minPrice())
                return false;
            if (filters.maxPrice() != null && cheapest > filters.maxPrice())
                return false;
        }

        // minEventRating / maxEventRating — event rating must fall within the range.
        if (filters.minEventRating() != null
                && (event.getRating() == null || event.getRating() < filters.minEventRating())) {
            return false;
        }
        if (filters.maxEventRating() != null
                && (event.getRating() == null || event.getRating() > filters.maxEventRating())) {
            return false;
        }

        // location — event venue city or country must match (case-insensitive
        // substring).
        if (filters.location() != null) {
            if (event.getVenueMap() == null || event.getVenueMap().getLocation() == null)
                return false;
            String locFilter = filters.location().toLowerCase();
            boolean cityMatch = event.getVenueMap().getLocation().city().toLowerCase().contains(locFilter);
            boolean countryMatch = event.getVenueMap().getLocation().country().toLowerCase().contains(locFilter);
            if (!cityMatch && !countryMatch) {
                return false;
            }
        }

        return true; // if the event passed into all the filters, it got to here and we'll return true
    }


}
