package com.ticketing.system.catalog.application.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

import com.ticketing.system.shared.domain.TextLimits;
import com.ticketing.system.shared.exception.*;

import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.shared.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.shared.dto.CompanySummaryDTO;
import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.shared.dto.EventSummaryDTO;
import com.ticketing.system.shared.dto.SearchResultDTO;
import com.ticketing.system.shared.dto.VenueMapDTO;
import com.ticketing.system.catalog.application.dtoMappers.VenueMapMapper;
import com.ticketing.system.catalog.application.dtoMappers.EventMapper;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.catalog.application.port.out.EventRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side of the event catalog, for guest and member buyers. UC-3 (Browse Events as Guest), UC-7
 * (Browse + Search Catalogs), UC-8 (View Venue Map + Inventory). It is separate from
 * {@link EventManagementService}, the owner-side write service, so the two audiences don't share an API
 * surface.
 *
 * <p>The methods that take a {@code credential} accept either a member's session token or a guest's
 * session id, and throw {@link InvalidTokenException} for any other value. The browse and filter
 * methods that take none are open to anyone. Only events of ACTIVE companies are visible, and an event
 * a buyer cannot see is reported as not found.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
public class CatalogService {

    private final SessionManager sessionManager;
    private final EventRepository eventRepository;
    private final ProductionCompanyRepository productionCompanyRepository;

    /**
     * Creates the service.
     *
     * @param sessionManager              validates credentials
     * @param eventRepository             the event store
     * @param productionCompanyRepository the company store, for visibility and names
     */
    public CatalogService(
            SessionManager sessionManager,
            EventRepository eventRepository,
            ProductionCompanyRepository productionCompanyRepository
    ) {
        this.sessionManager = sessionManager;
        this.eventRepository = eventRepository;
        this.productionCompanyRepository = productionCompanyRepository;
    }



    /**
     * UC-3: browses the catalog. Takes no credential: browsing is anonymous (auth rework #181 /
     * Phase 4.3).
     *
     * @return every ON_SALE event of an ACTIVE company; empty if there are none
     */
    public List<EventSummaryDTO> browseEventCatalog() {
        EventMapper mapper = new EventMapper();
        // Return all events that are ON_SALE and associated with an ACTIVE company.
        return productionCompanyRepository.findActive().stream()
                .flatMap(company -> eventRepository.findActiveByCompany(company.getCompanyId()).stream())
                .filter(event -> event.getStatus() == EventStatus.ON_SALE)
                .map(event -> mapper.convertEventToEventSummaryDTO(event, productionCompanyRepository))
                .toList();
    }




    /**
     * V2-LANDING-01: featured events for the public landing page. No credential is required: the landing
     * page is anonymous, like {@link #browseEventCatalog}. There is no curation flag on {@link Event}, so
     * "featured" is a rating-based heuristic for now.
     *
     * @param limit the most events to return; zero or less returns none
     * @return ON_SALE events of ACTIVE companies, best-rated first, with unrated events last
     */
    public List<EventSummaryDTO> featured(int limit) {
        EventMapper mapper = new EventMapper();
        return productionCompanyRepository.findActive().stream()
                .flatMap(company -> eventRepository.findActiveByCompany(company.getCompanyId()).stream())
                .sorted(Comparator.comparing(Event::getRating, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(0, limit))
                .map(event -> mapper.convertEventToEventSummaryDTO(event, productionCompanyRepository))
                .toList();
    }

    /**
     * Browse filter: the distinct countries that currently have publicly visible ON_SALE events. It is
     * gated on visibility exactly like {@link #searchGlobal}, so the dropdown never lists a country whose
     * only ON_SALE events belong to an inactive company, which browse would show zero results for. No
     * credential is required: the browse page is anonymous, like {@link #featured}.
     *
     * @return the countries, sorted ignoring case
     */
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

    /**
     * Browse filter: the distinct cities of publicly visible ON_SALE events in a country. No credential
     * is required.
     *
     * @param country the country, matched ignoring case
     * @return the cities, sorted ignoring case; empty if {@code country} is {@code null}
     */
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




    /**
     * V2-LANDING-01: the "on sale soon" teaser for the landing page. No credential is required. These
     * are SCHEDULED events (a venue map is bound but sales have not opened). {@code findActiveByCompany}
     * returns ON_SALE only, so they come from {@code findByStatus(SCHEDULED)}, and any whose company is
     * not active is dropped.
     *
     * @param limit the most events to return; zero or less returns none
     * @return SCHEDULED events of ACTIVE companies, soonest show first
     */
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
    // Compared as instants: events at venues in different zones sort by when they really start.
    private Instant earliestShowStart(Event event) {
        return event.getShowDates().stream()
                .map(show -> show.startsAt(event.getTimeZone()))
                .min(Comparator.naturalOrder())
                .orElse(null);
    }




    /**
     * UC-7: searches the catalog with filters. Open to anyone with an active session, a member or a guest
     * (UC-3 / UC-7, auth rework #181 / Phase 4.3). When a company-rating filter is set, an event matches
     * only if its company's derived rating (see {@link CompanyRatings}) is within it, and a company with
     * no rating fails any such bound.
     *
     * @param credential a member's session token or a guest's session id
     * @param filters    the filters; a {@code null} value or an absent filter means no restriction
     * @return the matching ON_SALE events of ACTIVE companies
     * @throws InvalidTokenException  if {@code credential} is neither
     * @throws IllegalArgumentException if a minimum exceeds its maximum or {@code fromDate} is after
     *                                {@code toDate}
     * @throws BusinessRuleViolationException if a text filter is too long; its message is shown to the user
     */
    public List<EventSummaryDTO> searchGlobal(String credential, CatalogSearchFiltersDTO filters) {
        log.debug("Starting global search with filters set: {}", filtersInUse(filters));
        // Validate the credential (JWT or sessionId) before proceeding with the search.
        if (!this.sessionManager.validateCredential(credential)) {
            log.warn("Invalid credential provided while performing global search with filters set: {}",
                    filtersInUse(filters));
            throw new InvalidTokenException("Invalid credential provided while performing global search");
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

        log.debug("Global search with filters set: {} returning {} results",
                filtersInUse(effectiveFilters), results.size());
        return results;
    }





    /**
     * UC-7: searches one company's events, like {@link #searchGlobal} but without the company-rating
     * filters, which are ignored.
     *
     * @param credential a member's session token or a guest's session id
     * @param companyId  the company
     * @param filters    the filters; a {@code null} value or an absent filter means no restriction
     * @return the company's matching ON_SALE events
     * @throws InvalidTokenException    if {@code credential} is neither
     * @throws CompanyNotFoundException if the company does not exist
     * @throws CompanyClosedException   if the company is not ACTIVE
     * @throws IllegalArgumentException if a minimum exceeds its maximum or {@code fromDate} is after
     *                                  {@code toDate}
     * @throws BusinessRuleViolationException if a text filter is too long; its message is shown to the user
     */
    public List<EventSummaryDTO> searchByCompany(String credential, int companyId, CatalogSearchFiltersDTO filters) {
        log.debug("Starting company-scoped search for companyId: {} with filters set: {}",
                companyId, filtersInUse(filters));
        // Validate the credential (JWT or sessionId) before proceeding with the search.
        if (!this.sessionManager.validateCredential(credential)) {
            log.warn("Invalid credential provided while performing company-scoped search for companyId: {} with filters set: {}",
                    companyId, filtersInUse(filters));
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
            // The event's company must still be active for the event to be publicly visible.
            ProductionCompany company = activeCompanyOrNull(event.getCompanyId());
            if (!isCompanyPubliclyVisible(company)) {
                continue;
            }

            results.add(mapper.convertEventToEventSummaryDTO(event, productionCompanyRepository));
        }

        log.debug("Company-scoped search for companyId: {} with filters set: {} returning {} results",
                companyId, filtersInUse(effectiveFilters), results.size());

        return results;
    }




    /**
     * V2-SEARCH-01 (#281): top-bar search across events, artists and venues. Same audience as browse and
     * search: a member or a guest. Matches the query (case-insensitive substring) over publicly visible
     * ON_SALE events from ACTIVE companies and emits typed rows ({@code EVENT}, {@code ARTIST},
     * {@code VENUE}); artists and venues are de-duplicated ignoring case. There are no dedicated artist
     * or venue pages, so every row points at a representative event (its EventDetailsView). Results are
     * interleaved across the three types, so one kind can't crowd out the others, then capped at
     * {@code limit}.
     *
     * @param credential a member's session token or a guest's session id
     * @param query      the text to find; surrounding spaces are trimmed
     * @param limit      the most rows to return
     * @return the rows; empty if {@code query} is {@code null} or blank, or {@code limit} is zero or less
     * @throws InvalidTokenException if {@code credential} is neither
     */
    public List<SearchResultDTO> search(String credential, String query, int limit) {
        if (!this.sessionManager.validateCredential(credential)) {
            log.warn("Invalid credential provided while performing top-bar search");
            throw new InvalidTokenException();
        }
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
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
            if (events.size() < limit && eventName != null && eventName.toLowerCase(Locale.ROOT).contains(q)) {
                events.add(new SearchResultDTO("EVENT", eventName, company.getName(), event.getId()));
            }

            if (artists.size() < limit && event.getArtistsNames() != null) {
                for (String artist : event.getArtistsNames()) {
                    if (artists.size() >= limit) {
                        break;
                    }
                    if (artist != null && artist.toLowerCase(Locale.ROOT).contains(q)
                            && seenArtists.add(artist.toLowerCase(Locale.ROOT))) {
                        artists.add(new SearchResultDTO("ARTIST", artist, "Artist · " + eventName, event.getId()));
                    }
                }
            }

            String venue = venueLabel(event);
            if (venues.size() < limit && venue != null && venue.toLowerCase(Locale.ROOT).contains(q)
                    && seenVenues.add(venue.toLowerCase(Locale.ROOT))) {
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
        log.debug("Top-bar search of {} characters returning {} results", q.length(), results.size());
        return results;
    }

    // A buyer can type anything into a search field, an email address included, so the search logs
    // name the filters in use and never their values.
    private static List<String> filtersInUse(CatalogSearchFiltersDTO filters) {
        if (filters == null) {
            return List.of();
        }
        Map<String, Object> byName = new LinkedHashMap<>();
        byName.put("eventName", filters.eventName());
        byName.put("artistName", filters.artistName());
        byName.put("category", filters.category());
        byName.put("keywords", filters.keywords());
        byName.put("minPrice", filters.minPrice());
        byName.put("maxPrice", filters.maxPrice());
        byName.put("fromDate", filters.fromDate());
        byName.put("toDate", filters.toDate());
        byName.put("location", filters.location());
        byName.put("minEventRating", filters.minEventRating());
        byName.put("maxEventRating", filters.maxEventRating());
        byName.put("minCompanyRating", filters.minCompanyRating());
        byName.put("maxCompanyRating", filters.maxCompanyRating());
        return byName.entrySet().stream()
                .filter(filter -> isSet(filter.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    private static boolean isSet(Object filterValue) {
        return filterValue != null && !(filterValue instanceof String text && text.isBlank());
    }

    /**
     * Finds the active production companies whose name contains a substring, ignoring case. Backs the
     * member "New Inquiry" company picker (II.3.10). No credential is needed: company names are public,
     * the same audience as browse.
     *
     * @param substring the text to find; a {@code null} or blank value matches every active company, so
     *                  the picker can show an initial list
     * @return the companies sorted by name ignoring case, each with its derived rating (see
     *         {@link CompanyRatings}), which is {@code null} for a company with no rated event
     */
    public List<CompanySummaryDTO> searchCompaniesByName(String substring) {
        String needle = substring == null ? "" : substring.trim().toLowerCase(Locale.ROOT);
        return productionCompanyRepository.findActive().stream()
                .filter(c -> needle.isEmpty()
                        || (c.getName() != null && c.getName().toLowerCase(Locale.ROOT).contains(needle)))
                .sorted(Comparator.comparing(ProductionCompany::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(c -> new CompanySummaryDTO(c.getCompanyId(), c.getName(),
                        CompanyRatings.fromEvents(eventRepository.findByCompanyId(c.getCompanyId()))))
                .toList();
    }

    /**
     * Returns a company's derived rating, the mean of its events' ratings (see {@link CompanyRatings}).
     * Backs the organizer line on the event-details page.
     *
     * @param companyId the company
     * @return the rating to one decimal place, or {@code null} when none of its events is rated
     */
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

        // Each term is bound as a LIKE parameter; one longer than any stored text can match nothing.
        TextLimits.requireFits("Event name", f.eventName());
        TextLimits.requireFits("Artist name", f.artistName());
        TextLimits.requireFits("Category", f.category());
        TextLimits.requireFits("Keywords", f.keywords());
        TextLimits.requireFits("Location", f.location());

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
    // Any other failure of the lookup propagates: a store that cannot answer is not a company that is not public.
    private ProductionCompany activeCompanyOrNull(int companyId) {
        ProductionCompany company;
        try {
            company = productionCompanyRepository.getCompanyById(companyId);
        } catch (CompanyNotFoundException e) {
            return null;
        }
        return company.getStatus() == CompanyStatus.ACTIVE ? company : null;
    }

    // *HELPER METHOD* to require that a ProductionCompany exists and is active, throwing CompanyNotFoundException or CompanyClosedException if not.
    private ProductionCompany requireActiveCompany(int companyId) {
        ProductionCompany company = productionCompanyRepository.getCompanyById(companyId);

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




    






    /**
     * UC-8: returns an event's venue map with per-seat and per-zone availability. Open to anyone with an
     * active session, a member or a guest (UC-3 / UC-8, auth rework #181 / Phase 4.3). The map is part
     * of the event's public page, so it is hidden exactly when the page is.
     *
     * @param credential a member's session token or a guest's session id
     * @param eventId    the event
     * @return the venue map
     * @throws InvalidTokenException  if {@code credential} is neither
     * @throws EventNotFoundException if there is no such event, its company is not ACTIVE, or the event
     *                                is still DRAFT or SCHEDULED
     * @throws NullVenueMapException  if the event has no venue map
     */
    public VenueMapDTO getEventVenueMap(String credential, int eventId) {
        log.debug("Fetching venue map for eventId: {}", eventId);

            if (!this.sessionManager.validateCredential(credential)) {
                log.warn("Invalid credential provided while getting venue map for eventId: {}", eventId);
                throw new InvalidTokenException();
            }
            
            // see that event exists and has a venue map
            Event event = this.eventRepository.findById(eventId);

            // Only a closed company's owners and the system admins may see its information, so on this
            // public read its event is refused exactly like a missing one (II.2.2).
            ProductionCompany company = productionCompanyRepository.getCompanyById(event.getCompanyId());
            if (company.getStatus() != CompanyStatus.ACTIVE) {
                log.warn("Attempt to access venue map for eventId: {} from inactive company", eventId);
                throw new EventNotFoundException(eventId);
            }

            // The map is part of the event's public page, so it is hidden exactly when the page is.
            requirePubliclyListed(event, "getting venue map");

            // see that event has a venue map
            if (event.getVenueMap() == null) {
                log.warn("Null venue map for event while getting venue map: {}", eventId);
                throw new NullVenueMapException(eventId);
            }

            log.debug("Venue map found for eventId: {} while getting venue map and being returned", eventId);
            return new VenueMapMapper().venueMapToVenueMapDTO(event.getVenueMap());

    }


    /**
     * UC-8: returns the public detail of one event for the buyer event page (header, description,
     * schedule, lineup). Same audience as browse and {@link #getEventVenueMap}: a member or a guest.
     *
     * @param credential a member's session token or a guest's session id
     * @param eventId    the event
     * @return the event's detail
     * @throws InvalidTokenException  if {@code credential} is neither
     * @throws EventNotFoundException if there is no such event, its company is not ACTIVE, or the event
     *                                is still DRAFT or SCHEDULED
     */
    public EventDetailDTO getEventDetail(String credential, int eventId) {
        log.debug("Fetching event detail for eventId: {}", eventId);

        if (!this.sessionManager.validateCredential(credential)) {
            log.warn("Invalid credential provided while getting event detail for eventId: {}", eventId);
            throw new InvalidTokenException();
        }

        Event event = this.eventRepository.findById(eventId);

        // Only a closed company's owners and the system admins may see its information, so on this
        // public read its event is refused exactly like a missing one (II.2.1).
        ProductionCompany company = productionCompanyRepository.getCompanyById(event.getCompanyId());
        if (company.getStatus() != CompanyStatus.ACTIVE) {
            log.warn("Attempt to access event detail for eventId: {} from inactive company", eventId);
            throw new EventNotFoundException(eventId);
        }

        requirePubliclyListed(event, "getting event detail");

        log.debug("Event detail found for eventId: {} and being returned", eventId);
        return new EventMapper().toEventDetailDTO(event, company.getName());
    }

    // DRAFT / SCHEDULED events are not yet public; ON_SALE / SOLD_OUT / CANCELED / COMPLETED are viewable
    // (the buyer page renders a status badge and disables purchasing for the non-purchasable ones). A
    // hidden event is reported as not found, so a caller cannot tell it from one that does not exist.
    private void requirePubliclyListed(Event event, String operation) {
        if (event.getStatus() == EventStatus.DRAFT || event.getStatus() == EventStatus.SCHEDULED) {
            log.warn("Attempt to access non-public event (status {}) while {} for eventId: {}",
                    event.getStatus(), operation, event.getId());
            throw new EventNotFoundException(event.getId());
        }
    }

}
