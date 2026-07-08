package com.ticketing.system.catalog.application.service;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.organization.application.service.CompanyRatings;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

import com.ticketing.system.shared.exception.*;

import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.Core.Application.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.Core.Application.dto.CompanySummaryDTO;
import com.ticketing.system.Core.Application.dto.EventDetailDTO;
import com.ticketing.system.Core.Application.dto.EventSummaryDTO;
import com.ticketing.system.Core.Application.dto.SearchResultDTO;
import com.ticketing.system.Core.Application.dto.VenueMapDTO;
import com.ticketing.system.Core.Application.dtoMappers.VenueMapMapper;
import com.ticketing.system.Core.Application.dtoMappers.EventMapper;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.Core.Domain.Tickets.ITicketRepository;
import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.catalog.application.port.out.EventRepository;

// Read-side service for guest- and visitor-facing catalog queries.
// Owns UC-3 (Browse Events as Guest), UC-7 (Browse + Search Catalogs), UC-8 (View Venue Map + Inventory).
// Separated from EventManagementService (which is owner-side / write-heavy) so the two audiences
// don't share an API surface — see design_walkthrough_summary.md §6.
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@Transactional(readOnly = true)
public class CatalogService {

    private final SessionManager sessionManager;
    private final EventRepository eventRepository;
    private final ProductionCompanyRepository productionCompanyRepository;
    private final ITicketRepository ticketRepository;

    public CatalogService(
            SessionManager sessionManager,
            EventRepository eventRepository,
            ProductionCompanyRepository productionCompanyRepository,
            ITicketRepository ticketRepository
    ) {
        this.sessionManager = sessionManager;
        this.eventRepository = eventRepository;
        this.productionCompanyRepository = productionCompanyRepository;
        this.ticketRepository = ticketRepository;
    }



    //* UC-3: Browse the catalog, returning a list of EventSummaryDTOs. Accepts either a JWT (Member) or a raw sessionId (Guest) — catalog 
    //* browsing is open to anyone with an active session per UC-3 / UC-7 (auth rework #181 / Phase 4.3).
    public List<EventSummaryDTO> browseEventCatalog() {
        EventMapper mapper = new EventMapper();
        // Return all events that are ON_SALE and associated with an ACTIVE company.
        return productionCompanyRepository.findActive().stream()
                .flatMap(company -> eventRepository.findActiveByCompany(company.getCompanyId()).stream())
                .filter(event -> event.getStatus() == EventStatus.ON_SALE)
                .map(event -> mapper.convertEventToEventSummaryDTO(event, productionCompanyRepository))
                .toList();
    }




    //* V2-LANDING-01: Featured events for the public landing page — the ON_SALE events from active companies,
    //* ordered best-rated first (events without a rating sort last), capped at {limit}. No credential required:
    //* the landing page is anonymous, like browseEventCatalog above. There is no curation flag on Event, so
    //* "featured" is a rating-based heuristic for now.
    public List<EventSummaryDTO> featured(int limit) {
        EventMapper mapper = new EventMapper();
        return productionCompanyRepository.findActive().stream()
                .flatMap(company -> eventRepository.findActiveByCompany(company.getCompanyId()).stream())
                .sorted(Comparator.comparing(Event::getRating, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(0, limit))
                .map(event -> mapper.convertEventToEventSummaryDTO(event, productionCompanyRepository))
                .toList();
    }

    //* Browse filters: distinct countries that currently have publicly-visible ON_SALE events, sorted.
    //* Visibility-gated exactly like searchGlobal so the dropdown never lists a country whose only ON_SALE
    //* events belong to an inactive company (which Browse would then show zero results for). No credential —
    //* the browse page is anonymous, like featured() above.
    public List<String> onSaleCountries() {
        Map<Integer, Boolean> visibleByCompany = new HashMap<>();
        return eventRepository.searchONSALE(CatalogSearchFiltersDTO.empty()).stream()
                .filter(e -> isCompanyPubliclyVisibleCached(e.getCompanyId(), visibleByCompany))
                .map(this::eventCountry)
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    //* Browse filters: distinct cities of publicly-visible ON_SALE events in the chosen country, sorted.
    public List<String> onSaleCitiesInCountry(String country) {
        if (country == null) return List.of();
        Map<Integer, Boolean> visibleByCompany = new HashMap<>();
        return eventRepository.searchONSALE(CatalogSearchFiltersDTO.empty()).stream()
                .filter(e -> isCompanyPubliclyVisibleCached(e.getCompanyId(), visibleByCompany))
                .filter(e -> country.equalsIgnoreCase(eventCountry(e)))
                .map(this::eventCity)
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    // *HELPER METHOD* — per-call memoized company-visibility lookup. Many ON_SALE events share a
    // company, so caching companyId→visibility avoids re-hitting the company repository once per
    // event (an N+1 under the JPA backend) while keeping the same semantics as a direct lookup.
    private boolean isCompanyPubliclyVisibleCached(int companyId, Map<Integer, Boolean> cache) {
        return cache.computeIfAbsent(companyId, id -> isCompanyPubliclyVisible(activeCompanyOrNull(id)));
    }




    //* V2-LANDING-01: "On sale soon" teaser for the landing page — SCHEDULED events (a venue map is bound but
    //* sales haven't opened) from active companies, soonest show date first, capped at {limit}. findActiveByCompany
    //* returns ON_SALE only, so we source these from findByStatus(SCHEDULED) and drop any whose company isn't active.
    public List<EventSummaryDTO> upcomingOnSale(int limit) {
        EventMapper mapper = new EventMapper();
        return eventRepository.findByStatus(EventStatus.SCHEDULED).stream()
                .filter(event -> activeCompanyOrNull(event.getCompanyId()) != null)
                .sorted(Comparator.comparing(this::earliestShowStart, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(Math.max(0, limit))
                .map(event -> mapper.convertEventToEventSummaryDTO(event, productionCompanyRepository))
                .toList();
    }

    // *HELPER METHOD* — earliest show start for an event, or null if it somehow has no show dates
    // (an Event invariant requires >=1, so the null branch only guards against future changes).
    private LocalDateTime earliestShowStart(Event event) {
        return event.getShowDates().stream()
                .map(ShowDate::getStartTime)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }




    //* UC-7: Search the catalog with filters, returning a list of EventSummaryDTOs. Accepts either a JWT (Member) or a raw sessionId (Guest) — 
    //* catalog search is open to anyone with an active session per UC-3 / UC-7 (auth rework #181 / Phase 4.3).
    public List<EventSummaryDTO> searchGlobal(String credential, CatalogSearchFiltersDTO filters) {
        log.info("Starting global search with filters: {}", filters);
        // Validate the credential (JWT or sessionId) before proceeding with the search.
        if (!this.sessionManager.validateCredential(credential)) {
            log.warn("Invalid credential provided while performing global search with filters: {}", filters);
            throw new InvalidTokenException("Invalid credential provided while performing global search with filters: " + filters);
        }
        // Normalize and validate the filters, THROWING ---> IllegalArgumentException for invalid ranges or dates.
        CatalogSearchFiltersDTO effectiveFilters = normalizeAndValidateCatalogFilters(filters);
        // Create an EventMapper to convert Event entities to EventSummaryDTOs.
        EventMapper mapper = new EventMapper();

        List<EventSummaryDTO> results = new ArrayList<>();

        // Company rating is DERIVED from a company's events (see CompanyRatings) — compute it only
        // when an Organizer-rating filter is active, cached per company for this search.
        boolean filterByCompanyRating =
                effectiveFilters.minCompanyRating() != null || effectiveFilters.maxCompanyRating() != null;
        Map<Integer, Double> companyRatingCache = new HashMap<>();

        // Iterate over all events returned by the eventRepository.searchONSALE() method, applying the effective filters.
        for (Event event : eventRepository.searchONSALE(effectiveFilters)) {
            // Check if the event is associated with an active company and is publicly visible (ON_SALE).
            ProductionCompany company = activeCompanyOrNull(event.getCompanyId());

            // If the event is not publicly visible or the company does not match the company rating filters, skip to the next event.
            if (!isCompanyPubliclyVisible(company)) {
                continue;
            }
            if (filterByCompanyRating) {
                // NOTE: don't use computeIfAbsent — a derived company rating is null when none of
                // the company's events are rated, and computeIfAbsent does NOT store a null result,
                // so it would re-query findByCompanyId for every event of an unrated company. Cache
                // the null explicitly via containsKey/put so each company is looked up at most once.
                Integer companyId = event.getCompanyId();
                Double companyRating;
                if (companyRatingCache.containsKey(companyId)) {
                    companyRating = companyRatingCache.get(companyId);
                } else {
                    companyRating = CompanyRatings.fromEvents(eventRepository.findByCompanyId(companyId));
                    companyRatingCache.put(companyId, companyRating);
                }
                if (!matchesCompanyRating(companyRating, effectiveFilters)) {
                    continue;
                }
            }

            results.add(mapper.convertEventToEventSummaryDTO(event, productionCompanyRepository));
        }

        log.info("Global search with filters: {} returning {} results", effectiveFilters, results.size());
        return results;
    }





    //* the searchByCompany method is similar to searchGlobal but scoped to a specific company and without company-rating filters.
    public List<EventSummaryDTO> searchByCompany(String credential, int companyId, CatalogSearchFiltersDTO filters) {
        log.info("Starting company-scoped search for companyId: {} with filters: {}", companyId, filters);
        // Validate the credential (JWT or sessionId) before proceeding with the search.
        if (!this.sessionManager.validateCredential(credential)) {
            log.warn("Invalid credential provided while performing company-scoped search for companyId: {} with filters: {}",
                    companyId, filters);
            throw new InvalidTokenException("Invalid credential provided while performing company-scoped search for companyId: " + companyId);
        }

        // Validate that the specified company exists and is active, throwing CompanyNotFoundException or CompanyClosedException if not.
        requireActiveCompany(companyId);

        // Normalize and validate the filters, THROWING ---> IllegalArgumentException for invalid ranges or dates. 
        // Without company-rating filters since this is a company-scoped search.
        CatalogSearchFiltersDTO effectiveFilters = normalizeAndValidateCatalogFilters(filters).withoutCompanyRating();
        EventMapper mapper = new EventMapper();

        List<EventSummaryDTO> results = new ArrayList<>();

        for (Event event : eventRepository.searchONSALE(effectiveFilters)) {

            if (event.getCompanyId() != companyId) {
                continue;
            }
            // Check if the event is associated with an active company and is publicly visible (ON_SALE). this check is 
            ProductionCompany company = activeCompanyOrNull(event.getCompanyId());
            if (!isCompanyPubliclyVisible(company)) {
                continue;
            }

            results.add(mapper.convertEventToEventSummaryDTO(event, productionCompanyRepository));
        }

        log.info("Company-scoped search for companyId: {} with filters: {} returning {} results",
                companyId, effectiveFilters, results.size());

        return results;
    }




    //* V2-SEARCH-01 (#281): Top-bar search across events, artists, and venues. Accepts a JWT (Member)
    //* or a raw sessionId (Guest) — same audience as browse/search. Matches the query (case-insensitive
    //* substring) over publicly-visible ON_SALE events from ACTIVE companies and emits typed rows;
    //* artists/venues are de-duplicated. There are no dedicated artist/venue pages, so every row points
    //* at a representative event (its EventDetailsView). Results are round-robined across the three types
    //* so one kind can't crowd out the others, then capped to {limit}.
    public List<SearchResultDTO> search(String credential, String query, int limit) {
        if (!this.sessionManager.validateCredential(credential)) {
            log.warn("Invalid credential provided while performing top-bar search");
            throw new InvalidTokenException();
        }
        String q = query == null ? "" : query.trim().toLowerCase();
        if (q.isEmpty() || limit <= 0) {
            return List.of();
        }

        List<SearchResultDTO> events = new ArrayList<>();
        List<SearchResultDTO> artists = new ArrayList<>();
        List<SearchResultDTO> venues = new ArrayList<>();
        Set<String> seenArtists = new LinkedHashSet<>();
        Set<String> seenVenues = new LinkedHashSet<>();

        for (Event event : eventRepository.findByStatus(EventStatus.ON_SALE)) {
            if (events.size() >= limit && artists.size() >= limit && venues.size() >= limit) {
                break; // every bucket is full — nothing more can surface
            }
            ProductionCompany company = activeCompanyOrNull(event.getCompanyId());
            if (company == null) {
                continue; // closed / unknown company — not publicly visible
            }

            String eventName = event.getName();
            if (events.size() < limit && eventName != null && eventName.toLowerCase().contains(q)) {
                events.add(new SearchResultDTO("EVENT", eventName, company.getName(), event.getId()));
            }

            if (artists.size() < limit && event.getArtistsNames() != null) {
                for (String artist : event.getArtistsNames()) {
                    if (artists.size() >= limit) {
                        break;
                    }
                    if (artist != null && artist.toLowerCase().contains(q)
                            && seenArtists.add(artist.toLowerCase())) {
                        artists.add(new SearchResultDTO("ARTIST", artist, "Artist · " + eventName, event.getId()));
                    }
                }
            }

            String venue = venueLabel(event);
            if (venues.size() < limit && venue != null && venue.toLowerCase().contains(q)
                    && seenVenues.add(venue.toLowerCase())) {
                venues.add(new SearchResultDTO("VENUE", venue, "Venue · " + eventName, event.getId()));
            }
        }

        List<SearchResultDTO> results = new ArrayList<>();
        boolean added = true;
        for (int i = 0; results.size() < limit && added; i++) {
            added = false;
            if (i < events.size())  { results.add(events.get(i));  added = true; if (results.size() == limit) break; }
            if (i < artists.size()) { results.add(artists.get(i)); added = true; if (results.size() == limit) break; }
            if (i < venues.size())  { results.add(venues.get(i));  added = true; if (results.size() == limit) break; }
        }
        log.info("Top-bar search for '{}' returning {} results", q, results.size());
        return results;
    }

    //* Active production companies whose name contains the (case-insensitive) query substring,
    //* sorted by name. Backs the member "New Inquiry" company picker (II.3.10). A blank query
    //* returns all active companies so the picker can show an initial list. No credential needed —
    //* company names are public (same audience as browse).
    public List<CompanySummaryDTO> searchCompaniesByName(String substring) {
        String needle = substring == null ? "" : substring.trim().toLowerCase();
        return productionCompanyRepository.findActive().stream()
                .filter(c -> needle.isEmpty()
                        || (c.getName() != null && c.getName().toLowerCase().contains(needle)))
                .sorted(Comparator.comparing(ProductionCompany::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(c -> new CompanySummaryDTO(c.getCompanyId(), c.getName(),
                        CompanyRatings.fromEvents(eventRepository.findByCompanyId(c.getCompanyId()))))
                .toList();
    }

    //* A company's DERIVED rating — the mean of its events' ratings (CompanyRatings), or null when
    //* it has no rated events. Backs the organizer line on the event-details page.
    public Double companyRating(int companyId) {
        return CompanyRatings.fromEvents(eventRepository.findByCompanyId(companyId));
    }

    // *HELPER METHOD* — "city, country" label for an event's venue, or null if it has no location.
    private String venueLabel(Event event) {
        if (event.getVenueMap() == null || event.getVenueMap().getLocation() == null) {
            return null;
        }
        return event.getVenueMap().getLocation().toString();
    }

    // *HELPER METHOD* — an event's country / city, or null when it has no bound venue/location.
    private String eventCountry(Event event) {
        return (event.getVenueMap() != null && event.getVenueMap().getLocation() != null)
                ? event.getVenueMap().getLocation().country() : null;
    }

    private String eventCity(Event event) {
        return (event.getVenueMap() != null && event.getVenueMap().getLocation() != null)
                ? event.getVenueMap().getLocation().city() : null;
    }

    






    // *HELPER METHOD* to normalize and validate the search filters, throwing IllegalArgumentException for invalid ranges or dates.
    private CatalogSearchFiltersDTO normalizeAndValidateCatalogFilters(CatalogSearchFiltersDTO filters) {
        CatalogSearchFiltersDTO f = filters == null
                ? CatalogSearchFiltersDTO.empty()
                : filters.normalized();

        validateDoubleRange(f.minPrice(), f.maxPrice(), "price");
        validateDoubleRange(f.minEventRating(), f.maxEventRating(), "event rating");
        validateDoubleRange(f.minCompanyRating(), f.maxCompanyRating(), "company rating");

        // validate that fromDate is before or equal to toDate if both are provided.
        if (f.fromDate() != null && f.toDate() != null && f.fromDate().isAfter(f.toDate())) {
            throw new IllegalArgumentException("fromDate must be before or equal to toDate");
        }

        return f;
    }

    // *HELPER METHOD* to validate that a min/max double range is valid, throwing IllegalArgumentException if min > max.
    private void validateDoubleRange(Double min, Double max, String name) {
        // if either min or max is null, we consider it unbounded and therefore valid, so only throw if both are non-null and min > max.
        if (min != null && max != null && min > max) {
            throw new IllegalArgumentException("min " + name + " must be <= max " + name);
        }
    }

    // *HELPER METHOD* to return the active ProductionCompany for a given companyId, or null if not found or not active.
    private ProductionCompany activeCompanyOrNull(int companyId) {
        try {
            ProductionCompany company = productionCompanyRepository.getCompanyById(companyId);
            // will throw if company not found, and if it doesn't throw but the company is not active, we'll return null to indicate that the company is not publicly visible.
            if (company == null || company.getStatus() != CompanyStatus.ACTIVE) {
                return null;
            }
            return company;
        } catch (RuntimeException e) {
            // If the company is not found or any other exception occurs, return null to indicate that the company does not exist.
            return null;
        }
    }

    // *HELPER METHOD* to require that a ProductionCompany exists and is active, throwing CompanyNotFoundException or CompanyClosedException if not.
    private ProductionCompany requireActiveCompany(int companyId) {
        ProductionCompany company;
        try {
            company = productionCompanyRepository.getCompanyById(companyId);
        } catch (RuntimeException e) {
            throw new CompanyNotFoundException(companyId);
        }

        if (company == null) {
            throw new CompanyNotFoundException(companyId);
        }

        if (company.getStatus() != CompanyStatus.ACTIVE) {
            throw new CompanyClosedException(companyId);
        }

        return company;
    }

    // *HELPER METHOD* to check if a ProductionCompany is publicly visible, i.e., ACTIVE.
    private boolean isCompanyPubliclyVisible(ProductionCompany company) {
        return company != null && company.getStatus() == CompanyStatus.ACTIVE;
    }

    // *HELPER METHOD* — does the company's DERIVED rating (mean of its events' ratings) satisfy the
    // min/max company-rating filters? A null rating (no rated events) fails any active bound.
    private boolean matchesCompanyRating(Double rating, CatalogSearchFiltersDTO filters) {
        if (filters.minCompanyRating() != null && (rating == null || rating < filters.minCompanyRating())) {
            return false;
        }
        if (filters.maxCompanyRating() != null && (rating == null || rating > filters.maxCompanyRating())) {
            return false;
        }
        return true;
    }




    






    // UC-8: Render venue map with per-seat / per-zone availability.
    // Accepts either a JWT (Member) or a raw sessionId (Guest) — venue map
    // viewing is open to anyone with an active session per UC-3 / UC-8
    // (auth rework #181 / Phase 4.3).
    public VenueMapDTO getEventVenueMap(String credential, int eventId) {
        log.info("Fetching venue map for eventId: {}", eventId);

            if (!this.sessionManager.validateCredential(credential)) {
                log.warn("Invalid credential provided while getting venue map for eventId: {}", eventId);
                throw new InvalidTokenException();
            }
            
            // see that event exists and has a venue map
            Event event = this.eventRepository.findById(eventId);
            if (event == null) {
                log.warn("Event not found while getting venue map: {}", eventId);
                throw new EventNotFoundException("Event with ID " + eventId + " not found while getting venue map");
            }

            // Additional check to enforce company status is ACTIVE.
            ProductionCompany company = productionCompanyRepository.getCompanyById(event.getCompanyId());
            if (company == null || company.getStatus() != CompanyStatus.ACTIVE) {
                log.warn("Attempt to access venue map for eventId: {} from inactive company", eventId);
                throw new CompanyClosedException("Event with ID " + eventId + " not found while getting venue map");
            }
            
            // see that event has a venue map
            if (event.getVenueMap() == null) {
                log.warn("Null venue map for event while getting venue map: {}", eventId);
                throw new NullVenueMapException(eventId);
            }

            log.info("Venue map found for eventId: {} while getting venue map and being returned", eventId);
            return new VenueMapMapper().venueMapToVenueMapDTO(event.getVenueMap());

    }


    // UC-8: Public single-event detail for the buyer event page (header, description, schedule, lineup).
    // Accepts either a JWT (Member) or a raw sessionId (Guest) — same audience as browse / getEventVenueMap.
    public EventDetailDTO getEventDetail(String credential, int eventId) {
        log.info("Fetching event detail for eventId: {}", eventId);

        if (!this.sessionManager.validateCredential(credential)) {
            log.warn("Invalid credential provided while getting event detail for eventId: {}", eventId);
            throw new InvalidTokenException();
        }

        Event event = this.eventRepository.findById(eventId);
        if (event == null) {
            log.warn("Event not found while getting event detail: {}", eventId);
            throw new EventNotFoundException("Event with ID " + eventId + " not found while getting event detail");
        }

        // Enforce company status is ACTIVE — closed companies' events are not publicly visible.
        ProductionCompany company = productionCompanyRepository.getCompanyById(event.getCompanyId());
        if (company == null || company.getStatus() != CompanyStatus.ACTIVE) {
            log.warn("Attempt to access event detail for eventId: {} from inactive company", eventId);
            throw new CompanyClosedException("Event with ID " + eventId + " not found while getting event detail");
        }

        // DRAFT / SCHEDULED events are not yet public; ON_SALE / SOLD_OUT / CANCELED / COMPLETED are viewable
        // (the buyer page renders a status badge and disables purchasing for the non-purchasable ones).
        if (event.getStatus() == EventStatus.DRAFT || event.getStatus() == EventStatus.SCHEDULED) {
            log.warn("Attempt to access non-public event detail (status {}) for eventId: {}", event.getStatus(), eventId);
            throw new EventNotFoundException("Event with ID " + eventId + " not found while getting event detail");
        }

        log.info("Event detail found for eventId: {} and being returned", eventId);
        return new EventMapper().toEventDetailDTO(event, company.getName());
    }

}
