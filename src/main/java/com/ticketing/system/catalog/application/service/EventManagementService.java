package com.ticketing.system.catalog.application.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.CompanyNotFoundException;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.shared.exception.UserNotFoundException;
import com.ticketing.system.catalog.application.dto.EventCreationDTO;
import com.ticketing.system.shared.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.shared.dto.EventPolicyConfigDTO;
import com.ticketing.system.shared.dto.EventUpdateDTO;
import com.ticketing.system.shared.dto.PurchasePolicyDTO;
import com.ticketing.system.shared.dto.PurchasePolicyDtoMapper;
import com.ticketing.system.shared.dto.GridPlacementDTO;
import com.ticketing.system.shared.dto.VenueLayoutDTO;
import com.ticketing.system.catalog.application.dtoMappers.EventMapper;
import com.ticketing.system.shared.dto.VenueMapConfigDTO;
import com.ticketing.system.shared.dto.ZoneDetailDTO;
import com.ticketing.system.identity.application.port.out.SessionManager;
import org.springframework.context.ApplicationEventPublisher;
import com.ticketing.system.catalog.domain.event.EventCancelledEvent;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.application.service.CompanyMembershipService;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.application.port.out.EventSalesHistoryPort;
import com.ticketing.system.catalog.config.VenueLimitsProperties;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.InventoryZone;
import com.ticketing.system.catalog.domain.StandingZone;
import com.ticketing.system.catalog.domain.VenueMap;
import com.ticketing.system.catalog.domain.DiscountPolicy;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.catalog.domain.Seat;
import com.ticketing.system.catalog.domain.SeatedZone;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.shared.domain.policy.PurchasePolicy;

/**
 * The owner and manager side of the event catalog: it creates, edits, publishes, cancels and deletes
 * {@link Event}s and edits their venue maps and policies. UC-19 (Manage Event Catalog), UC-20
 * (Configure Venue Map &amp; Inventory), UC-21 (Configure Policies). The buyer side is
 * {@link CatalogService}.
 *
 * <p>Every method takes the caller's session token. It must be a live member token, or the method throws
 * {@link InvalidTokenException}; a token whose member no longer exists throws
 * {@link UserNotFoundException}. The method then checks that the member holds the permission it needs
 * in the event's company (a missing one throws {@link UnauthorizedActionException}). The permission is
 * {@code MANAGE_INVENTORY} for event details and lifecycle, {@code CONFIGURE_VENUE} for the venue map
 * and its zones, and {@code EDIT_POLICIES} for purchase policies. The exceptions are
 * {@link #listEventsForCompany} and {@link #getEventDetail}, which need only active membership of the
 * company.
 *
 * <p>A write to an existing event locks it with {@code EventRepository.lockForUpdate} for its duration.
 * On the Jpa adapter that lock is a no-op. Writers changing the same event, zone or seat row are
 * separated by that row's {@code @Version}, and the loser gets {@code ConcurrentReservationException}.
 * {@code venue_maps} has no {@code @Version}, so a write that changes only that row is last-write-wins.
 * {@link #cancelEventAndRefund} is deliberately not {@code @Transactional}; see there.
 *
 * <p>Event creation and venue-map configuration are separate steps: creation needs only the venue's
 * location, and the zones are configured, and reconfigured, in later calls before the event goes on
 * sale.
 */
@Service
@Slf4j
public class EventManagementService {

    private final EventRepository eventRepository;
    private final ProductionCompanyRepository companyRepository;
    private final SessionManager sessionManager;
    // Catalog-owned port (implemented by a sales adapter) used only to guard deleteEvent against
    // orphaning sales records — keeps catalog free of any sales import.
    private final EventSalesHistoryPort eventSalesHistoryPort;
    private final UserRepository userRepository;
    // Company-membership/authorization checks moved off the User aggregate (task #20); catalog already
    // depends on organization, so this downward edge is allowed.
    private final CompanyMembershipService companyMembershipService;
    // Publisher for cross-context domain/integration events. On cancel it emits an
    // EventCancelledEvent that the sales context's listener turns into the refund flow, and the
    // notifications context ultimately raises holder notices — catalog never calls either directly.
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    // Server-side venue size caps (OQ-27): the editor's field limits are advisory, so every write that
    // sizes a venue checks them here.
    private final VenueLimitsProperties venueLimits;
    private int currentVenueMapIdCounter;

    /**
     * Creates the service.
     *
     * @param eventRepository          the event store
     * @param companyRepository        the company store, for company lookups
     * @param sessionManager           validates session tokens
     * @param eventSalesHistoryPort    answers whether an event has sales history, for {@link #deleteEvent}
     * @param userRepository           the member store
     * @param companyMembershipService checks a member's permissions in a company
     * @param eventPublisher           publishes {@link EventCancelledEvent}
     * @param clock                    the application clock; a schedule must start after its instant
     * @param venueLimits              the venue size caps
     */
    public EventManagementService(
            EventRepository eventRepository,
            ProductionCompanyRepository companyRepository,
            SessionManager sessionManager,
            EventSalesHistoryPort eventSalesHistoryPort,
            UserRepository userRepository,
            CompanyMembershipService companyMembershipService,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            VenueLimitsProperties venueLimits) {
        this.eventRepository = eventRepository;
        this.companyRepository = companyRepository;
        this.sessionManager = sessionManager;
        this.eventSalesHistoryPort = eventSalesHistoryPort;
        this.userRepository = userRepository;
        this.companyMembershipService = companyMembershipService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.venueLimits = venueLimits;
        // Not read anywhere: venue map ids come from EventRepository.nextVenueMapId().
        this.currentVenueMapIdCounter = 0;
    }

    /**
     * UC-19: a company member with {@code MANAGE_INVENTORY} adds an event in DRAFT state. The event gets
     * an empty venue map at the request's location, a 0% discount, and the purchase policy the request
     * describes.
     *
     * @param token   the member's session token
     * @param request the event's details; its location is required
     * @return the new event
     * @throws InvalidTokenException          if {@code token} is not a live member token
     * @throws IllegalArgumentException       if the request has no venue location, checked before the
     *                                        company lookup; if an artist's name is null or blank; if a
     *                                        show starts at or before the clock's instant at the venue; or
     *                                        if the purchase policy DTO is malformed
     * @throws CompanyNotFoundException       if the company does not exist
     * @throws UnauthorizedActionException    if the member lacks {@code MANAGE_INVENTORY} in the company
     * @throws BusinessRuleViolationException if the name, description or an artist's name is too long;
     *                                        its message is shown to the user
     * @throws IllegalStateException          if the details break an {@link Event} invariant, such as an
     *                                        empty lineup or schedule
     */
    @Transactional
    public EventDetailDTO addEvent(String token, EventCreationDTO request) {
        int ownerId = validateTokenAndGetUserId(token);
        if (request.location() == null) {
            throw new IllegalArgumentException("An event needs a venue location");
        }

        ProductionCompany company;
        try {
            company = companyRepository.getCompanyById(request.companyId());
        } catch (CompanyNotFoundException e) {
            log.warn("Request rejected: company not found. companyId={}", request.companyId());
            throw new CompanyNotFoundException();
        }

        User user = userRepository.getUserById(ownerId);
        companyMembershipService.requirePermissionInCompany(user.getUserId(),request.companyId(), Permission.MANAGE_INVENTORY);

        int newEventId = eventRepository.nextId();
        VenueMap venueMap = new VenueMap(eventRepository.nextVenueMapId(), request.location(), List.of());
        // No path sets an event's discount yet (OQ-36, NaimElijah/Ticketeer#259), so addEvent passes a 0% policy.
        DiscountPolicy discountPolicy = new DiscountPolicy(0);

        
        PurchasePolicy eventSpecificPurchasePolicy = PurchasePolicyDtoMapper.toPolicy(request.purchasePolicy());
       
        Event newEvent = new Event(
                newEventId,
                request.name(),
                request.description(),
                request.rating(),
                request.artistsNames(),
                request.category(),
                request.companyId(),
                EventStatus.DRAFT,
                venueMap,
                request.showDates(),
                request.timeZone(),
                eventSpecificPurchasePolicy ,
                discountPolicy);
        newEvent.requireShowsStartAfter(clock.instant());
        eventRepository.save(newEvent);

        log.info("Event {} created successfully with ID {}", request.name(), newEventId);
        return new EventMapper().toEventDetailDTO(newEvent, company.getName());
    }

    /**
     * II.4.1.1: lists every event of a company, in any status, for the company's workspace. Any active
     * member of the company may list them.
     *
     * @param token     the member's session token
     * @param companyId the company
     * @return the company's events; empty if it has none
     * @throws InvalidTokenException       if {@code token} is not a live member token
     * @throws CompanyNotFoundException    if the company does not exist
     * @throws UnauthorizedActionException if the member is not an active member of the company
     */
    @Transactional(readOnly = true)
    public List<EventDetailDTO> listEventsForCompany(String token, int companyId) {
        return listEventsForCompany(token, companyId, CatalogSearchFiltersDTO.empty());
    }

    /**
     * II.4.1.1: lists a company's events, in any status, narrowed by the catalogue filters. The
     * company-rating filters are ignored, since the events all belong to one company. Any active member of
     * the company may list them.
     *
     * @param token     the member's session token
     * @param companyId the company
     * @param filters   the filters to narrow by, with an absent filter meaning no restriction; not null
     * @return the matching events; empty if there are none
     * @throws InvalidTokenException       if {@code token} is not a live member token
     * @throws CompanyNotFoundException    if the company does not exist
     * @throws UnauthorizedActionException if the member is not an active member of the company
     */
    @Transactional(readOnly = true)
    public List<EventDetailDTO> listEventsForCompany(String token, int companyId, CatalogSearchFiltersDTO filters) {
        int userId = validateTokenAndGetUserId(token);
        User user = userRepository.getUserById(userId);
        if (user == null) {
            throw new UserNotFoundException();
        }

        ProductionCompany company = companyRepository.getCompanyById(companyId);

        // The events list is a read-only management hub. Any active member of the company may see
        // it (so e.g. a venue/sales manager can reach the per-event venue editor); the per-event
        // mutations (configure venue, edit details, policies, cancel) stay gated by their own
        // permission at their own service entry points.
        companyMembershipService.requireMemberInCompany(user.getUserId(),companyId);

        EventMapper mapper = new EventMapper();
        return eventRepository.searchByCompanyAll(companyId, filters.withoutCompanyRating()).stream()
            .map(e -> mapper.toEventDetailDTO(e, company.getName()))
            .toList();
    }

    /**
     * Returns an event's summary for a member who manages the company's inventory. The returned DTO's
     * description is {@code null}; {@link #getEventDetail} returns it filled.
     *
     * @param token   the member's session token
     * @param eventId the event
     * @return the event's detail, without its description
     * @throws InvalidTokenException       if {@code token} is not a live member token
     * @throws EventNotFoundException      if there is no such event
     * @throws UnauthorizedActionException if the member lacks {@code MANAGE_INVENTORY} in the event's
     *                                     company
     */
    @Transactional(readOnly = true)
    public EventDetailDTO getEvent(String token, int eventId) {
        int userId = validateTokenAndGetUserId(token);
        Event event = eventRepository.findById(eventId);
        User user = userRepository.getUserById(userId);
        if (user == null) {
            throw new UserNotFoundException();
        }
        companyMembershipService.requirePermissionInCompany(user.getUserId(),event.getCompanyId(), Permission.MANAGE_INVENTORY);
        ProductionCompany company = companyRepository.getCompanyById(event.getCompanyId());

        return new EventDetailDTO(
            String.valueOf(event.getId()),
            event.getName(),
            event.getRating(),
            null,
            event.getCategory(),
            event.getVenueMap() != null ? event.getVenueMap().getLocation() : null,
            String.valueOf(event.getCompanyId()),
            company.getName(),
            event.getStatus(),
            event.getShowDates(),
            event.getTimeZone(),
            event.getArtistsNames(),
            EventMapper.minZonePrice(event)
            );
    }

    



    /**
     * II.4.2.3: reads back an event's current venue layout, so the editor shows the layout as stored.
     * A standing zone is reported with its capacity. A seated zone is reported as a number of rows and the
     * largest number of seats in any row, where a seat's row is the leading run of non-digit characters
     * in its label, so a label such as {@code AA12} belongs to row {@code AA}.
     *
     * @param token   the member's session token
     * @param eventId the event
     * @return the layout; an event with no venue map yields the default grid with no zones
     * @throws InvalidTokenException       if {@code token} is not a live member token
     * @throws EventNotFoundException      if there is no such event
     * @throws UnauthorizedActionException if the member lacks {@code CONFIGURE_VENUE} in the event's
     *                                     company
     */
    @Transactional(readOnly = true)
    public VenueLayoutDTO getEventZones(String token, int eventId) {
        int userId = validateTokenAndGetUserId(token);
        User user = userRepository.getUserById(userId);
        if (user == null) {
            throw new UserNotFoundException();
        }
        Event event = eventRepository.findById(eventId);
        companyMembershipService.requirePermissionInCompany(user.getUserId(),event.getCompanyId(), Permission.CONFIGURE_VENUE);

        VenueMap map = event.getVenueMap();
        if (map == null) {
            return new VenueLayoutDTO(VenueMap.DEFAULT_GRID_ROWS, VenueMap.DEFAULT_GRID_COLS, List.of());
        }
        if (map.getInventoryZones().isEmpty()) {
            return new VenueLayoutDTO(map.getGridRows(), map.getGridCols(), List.of());
        }

        List<ZoneDetailDTO> result = new ArrayList<>();
        for (InventoryZone zone : map.getInventoryZones()) {
            GridPlacementDTO placement = zone.hasGridPlacement()
                    ? new GridPlacementDTO(zone.getGridRow(), zone.getGridCol(),
                            zone.getGridRowSpan(), zone.getGridColSpan())
                    : null;
            if (zone.isSeated()) {
                SeatedZone sz = (SeatedZone) zone;
                List<Seat> seats = sz.getSeats();
                // Seat labels are "<rowLabel><number>" where rowLabel may be multi-char
                // (e.g. "AA12"). Group by the leading non-digit run so the row/seat-per-row
                // counts stay accurate for multi-char rows and >26 rows — not just charAt(0).
                Map<String, Integer> seatsByRow = new LinkedHashMap<>();
                for (Seat s : seats) {
                    String label = s.getLabel();
                    int i = 0;
                    while (i < label.length() && !Character.isDigit(label.charAt(i)))
                        i++;
                    String rowLabel = i > 0 ? label.substring(0, i) : label;
                    seatsByRow.merge(rowLabel, 1, Integer::sum);
                }
                int rows = seatsByRow.size();
                int seatsPerRow = seatsByRow.values().stream().mapToInt(Integer::intValue).max().orElse(0);
                result.add(new ZoneDetailDTO(zone.getName(), true, rows, seatsPerRow, 0, zone.getprice(), placement));
            } else {
                result.add(new ZoneDetailDTO(
                        zone.getName(), false, 0, 0, zone.getCapacity(), zone.getprice(), placement));
            }
        }
        return new VenueLayoutDTO(map.getGridRows(), map.getGridCols(), result);
    }

    /**
     * UC-20: replaces an event's venue map with the zones in {@code config}, and can be called any number
     * of times before the event goes on sale. The zones take ids 1 to N in the order given. The new map
     * keeps the stored venue location. A grid size of zero or less in {@code config} means the default
     * grid. Binding a map with at least one zone to a DRAFT event makes it SCHEDULED. The map is checked
     * against the {@code catalog.venue-limits} caps before any domain object is built.
     *
     * @param token     the member's session token
     * @param companyId the company that owns the event
     * @param config    the event id (as text), the grid size and the zones, with their placements
     * @throws InvalidTokenException        if {@code token} is not a live member token
     * @throws UnauthorizedActionException  if the member lacks {@code CONFIGURE_VENUE} in the company, or
     *                                      the event belongs to another company
     * @throws EventNotFoundException       if there is no such event
     * @throws IllegalArgumentException     if {@code config.eventId()} is not an integer, the venue is over
     *                                      a limit, a standing zone's capacity is not positive, a seated
     *                                      zone has no seats or repeats a seat label, two zones share a
     *                                      name (ignoring case), or a placement runs past the grid; the
     *                                      message is shown to the user
     * @throws BusinessRuleViolationException if a zone name is too long
     * @throws IllegalStateException        if the event has no stored venue location, a zone name or a
     *                                      seat label is blank or a price is out of range, a placement
     *                                      overlaps another zone, or {@link Event} refuses the map (see
     *                                      {@link Event#configureVenueMap})
     */
    @Transactional
    public void configureVenueMap(String token, int companyId, VenueMapConfigDTO config) {
        int eventId = Integer.parseInt(config.eventId());

        eventRepository.lockForUpdate(eventId);
        try {
            int userId = validateTokenAndGetUserId(token);

            log.info("Configuring venue map for company {}, event {}, by user {}", companyId, config.eventId(), userId);

            User user = userRepository.getUserById(userId);
            companyMembershipService.requirePermissionInCompany(user.getUserId(),companyId, Permission.CONFIGURE_VENUE);

            Event event = eventRepository.findById(eventId);

            if (event.getCompanyId() != companyId) {
                throw new UnauthorizedActionException("act on an event that does not belong to this company");
            }

            // The new map keeps the stored location, and a stored map can predate the rule that a venue has one
            // (V1 allows none), so say what to do first rather than only refuse.
            Location location = event.getVenueMap() == null ? null : event.getVenueMap().getLocation();
            if (location == null) {
                throw new IllegalStateException(
                        "Set the event's venue location in Edit details before configuring its venue map");
            }

            int gridRows = config.gridRows() > 0 ? config.gridRows() : VenueMap.DEFAULT_GRID_ROWS;
            int gridCols = config.gridCols() > 0 ? config.gridCols() : VenueMap.DEFAULT_GRID_COLS;
            requireVenueWithinLimits(config.zones(), gridRows, gridCols);

            List<InventoryZone> zones = new ArrayList<>();
            int nextZoneId = 1;

            for (VenueMapConfigDTO.ZoneConfigDTO zoneConfig : config.zones()) {
                if (zoneConfig.seated()) {
                    List<Seat> seats = toDomainSeats(zoneConfig.seats());
                    zones.add(new SeatedZone(
                            nextZoneId,
                            zoneConfig.zoneName(),
                            zoneConfig.pricePerTicket(),
                            seats));
                } else {
                    if (zoneConfig.capacity() == null || zoneConfig.capacity() <= 0) {
                        throw new IllegalArgumentException("Standing zone capacity must be positive");
                    }

                    zones.add(new StandingZone(
                            nextZoneId,
                            zoneConfig.zoneName(),
                            zoneConfig.capacity(),
                            zoneConfig.pricePerTicket()));
                }

                nextZoneId++;
            }

            VenueMap venueMap = new VenueMap(
                    event.getVenueMap() != null ? event.getVenueMap().getId() : eventRepository.nextVenueMapId(),
                    location, zones, gridRows, gridCols);

            // Apply each zone's grid placement. Zone IDs were assigned 1..N in config
            // order,
            // so the same iteration order maps each config back to its zone. Bounds +
            // overlap
            // are validated by VenueMap.placeZoneOnGrid.
            int placementZoneId = 1;
            for (VenueMapConfigDTO.ZoneConfigDTO zoneConfig : config.zones()) {
                GridPlacementDTO placement = zoneConfig.placement();
                if (placement != null) {
                    venueMap.placeZoneOnGrid(placementZoneId, placement.row(), placement.col(),
                            placement.rowSpan(), placement.colSpan());
                }
                placementZoneId++;
            }

            event.configureVenueMap(venueMap, companyId);
            eventRepository.save(event);

            log.info("Venue map for event {} configured successfully", event.getId());
        } finally {
            eventRepository.unlock(eventId);
        }
    }

    /**
     * UC-19: updates an event's details; an absent field leaves the stored one alone, and
     * {@link Event#editDetails} enforces when an edit is allowed. The category is matched by name,
     * ignoring case.
     *
     * @param token  the member's session token
     * @param update the event id (as text) and the new details
     * @throws InvalidTokenException        if {@code token} is not a live member token
     * @throws IllegalArgumentException     if the event id is not an integer or the category is unknown
     *                                      (the message is shown to the user), or {@link Event#editDetails}
     *                                      refuses a schedule
     * @throws EventNotFoundException       if there is no such event
     * @throws UnauthorizedActionException  if the member lacks {@code MANAGE_INVENTORY} in the event's
     *                                      company
     * @throws IllegalStateException        if the event is past SCHEDULED, or the edit breaks an invariant
     * @throws BusinessRuleViolationException if the name or description is too long; its message is
     *                                      shown to the user
     */
    @Transactional
    public void editEventDetails(String token, EventUpdateDTO update) {
        int userId = validateTokenAndGetUserId(token);

        int eventId;
        try {
            eventId = Integer.parseInt(update.eventId());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid event ID: " + update.eventId());
        }

        eventRepository.lockForUpdate(eventId);
        try {
            Event event = eventRepository.findById(eventId);

            User user = userRepository.getUserById(userId);
            companyMembershipService.requirePermissionInCompany(user.getUserId(),event.getCompanyId(), Permission.MANAGE_INVENTORY);

            EventCategory newCategory = null;
            if (update.category() != null && !update.category().isBlank()) {
                try {
                    newCategory = EventCategory.valueOf(update.category().trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Unknown event category: " + update.category());
                }
            }

            Location newLocation = update.location() == null
                    ? null
                    : new Location(update.location().country(), update.location().city());

            List<ShowDate> newShowDates = update.showDates() == null
                    ? null
                    : update.showDates().stream()
                            .map(sd -> new ShowDate(sd.startsAt(), sd.endsAt()))
                            .toList();

            event.editDetails(update.name(), update.description(), newCategory, newLocation, update.timeZone(),
                    newShowDates, clock.instant());
            eventRepository.save(event);

            log.info("Event {} details updated by user {}", eventId, userId);
        } finally {
            eventRepository.unlock(eventId);
        }
    }

    /**
     * UC-20: adds one zone to an event's venue map, with an optional grid placement. The map must stay
     * within the {@code catalog.venue-limits} caps and hold no more zones than the grid has cells.
     *
     * @param token      the member's session token
     * @param companyId  the company that owns the event
     * @param eventId    the event
     * @param zoneConfig the zone: name, price, and either a capacity or seats; may carry a placement
     * @return the new zone's id
     * @throws InvalidTokenException        if {@code token} is not a live member token
     * @throws CompanyNotFoundException     if the company does not exist
     * @throws UnauthorizedActionException  if the member lacks {@code CONFIGURE_VENUE} in the company, or
     *                                      the event belongs to another company
     * @throws EventNotFoundException       if there is no such event
     * @throws IllegalArgumentException     if {@code zoneConfig} is {@code null}, a limit or the grid is
     *                                      exceeded, a standing zone's capacity is not positive, the seats
     *                                      are missing or repeat a label, another zone has the name
     *                                      (ignoring case), or a placement runs past the grid; the
     *                                      message is written for the user
     * @throws IllegalStateException        if the event is past SCHEDULED, the zone name or a seat label
     *                                      is blank or the price is out of range, or a placement
     *                                      overlaps another zone
     * @throws BusinessRuleViolationException if the event has no venue map, or the zone name is too long
     */
    @Transactional
    public int addInventoryZone(String token, int companyId, int eventId, VenueMapConfigDTO.ZoneConfigDTO zoneConfig) {

        eventRepository.lockForUpdate(eventId);

        try {
            if (zoneConfig == null) {
                throw new IllegalArgumentException("Zone config cannot be null");
            }

            Event event = getAuthorizedEventForVenueEdit(token, companyId, eventId);
            requireZoneWithinLimits(zoneConfig);
            requireFreeGridCell(event);
            if (zoneConfig.seated()) {
                requireMapSeatsWithinLimit(event, seatCount(zoneConfig));
            }

            int newZoneId;

            if (zoneConfig.seated()) {
                List<Seat> seats = toDomainSeats(zoneConfig.seats());

                newZoneId = event.addSeatedZone(
                        zoneConfig.zoneName(),
                        zoneConfig.pricePerTicket(),
                        seats,
                        companyId);
            } else {
                if (zoneConfig.capacity() == null || zoneConfig.capacity() <= 0) {
                    throw new IllegalArgumentException("Standing zone capacity must be positive");
                }

                newZoneId = event.addStandingZone(
                        zoneConfig.zoneName(),
                        zoneConfig.capacity(),
                        zoneConfig.pricePerTicket(),
                        companyId);
            }

            if (zoneConfig.placement() != null) {
                GridPlacementDTO p = zoneConfig.placement();
                event.getVenueMap().placeZoneOnGrid(newZoneId, p.row(), p.col(), p.rowSpan(), p.colSpan());
            }

            eventRepository.save(event);

            log.info("Added inventory zone {} to event {} in company {}", newZoneId, eventId, companyId);

            return newZoneId;
        } finally {
            eventRepository.unlock(eventId);
        }
    }

    /**
     * UC-20: removes one zone from an event's venue map.
     *
     * @param token     the member's session token
     * @param companyId the company that owns the event
     * @param eventId   the event
     * @param zoneId    the zone to remove
     * @throws InvalidTokenException        if {@code token} is not a live member token
     * @throws CompanyNotFoundException     if the company does not exist
     * @throws UnauthorizedActionException  if the member lacks {@code CONFIGURE_VENUE} in the company, or
     *                                      the event belongs to another company
     * @throws EventNotFoundException       if there is no such event
     * @throws IllegalArgumentException     if the map has no such zone
     * @throws IllegalStateException        see {@link Event#removeInventoryZone}
     * @throws BusinessRuleViolationException if the event has no venue map
     */
    @Transactional
    public void removeInventoryZone(String token, int companyId, int eventId, int zoneId) {

        eventRepository.lockForUpdate(eventId);

        try {
            Event event = getAuthorizedEventForVenueEdit(token, companyId, eventId);

            event.removeInventoryZone(zoneId, companyId);

            eventRepository.save(event);

            log.info("Removed inventory zone {} from event {} in company {}", zoneId, eventId, companyId);
        } finally {
            eventRepository.unlock(eventId);
        }
    }

    /**
     * UC-20: adds places to a standing zone of an event. The zone's capacity must stay within the
     * {@code catalog.venue-limits} cap.
     *
     * @param token       the member's session token
     * @param companyId   the company that owns the event
     * @param eventId     the event
     * @param zoneId      the standing zone
     * @param placesToAdd the number of places to add; positive
     * @throws InvalidTokenException        if {@code token} is not a live member token
     * @throws CompanyNotFoundException     if the company does not exist
     * @throws UnauthorizedActionException  if the member lacks {@code CONFIGURE_VENUE} in the company, or
     *                                      the event belongs to another company
     * @throws EventNotFoundException       if there is no such event
     * @throws IllegalArgumentException     if the map has no such zone, {@code placesToAdd} is not
     *                                      positive, or the capacity would pass the cap (the message is
     *                                      written for the user)
     * @throws IllegalStateException        if the event is past SCHEDULED, or the zone is not standing
     * @throws BusinessRuleViolationException if the event has no venue map
     */
    @Transactional
    public void addPlacesToStandingZone(String token, int companyId, int eventId, int zoneId, int placesToAdd) {
        eventRepository.lockForUpdate(eventId);

        try {
            Event event = getAuthorizedEventForVenueEdit(token, companyId, eventId);
            requireStandingCapacityWithinLimit(event, zoneId, placesToAdd);
            event.addPlacesToStandingZone(zoneId, placesToAdd, companyId);
            eventRepository.save(event);

            log.info("Added {} places to standing zone {} in event {}", placesToAdd, zoneId, eventId);
        } finally {
            eventRepository.unlock(eventId);
        }
    }

    /**
     * UC-20: removes places from a standing zone of an event; only available places can go.
     *
     * @param token          the member's session token
     * @param companyId      the company that owns the event
     * @param eventId        the event
     * @param zoneId         the standing zone
     * @param placesToRemove the number of places to remove; positive and at most the zone's available
     *                       places
     * @throws InvalidTokenException        if {@code token} is not a live member token
     * @throws CompanyNotFoundException     if the company does not exist
     * @throws UnauthorizedActionException  if the member lacks {@code CONFIGURE_VENUE} in the company, or
     *                                      the event belongs to another company
     * @throws EventNotFoundException       if there is no such event
     * @throws IllegalArgumentException     if the map has no such zone, or {@code placesToRemove} is not
     *                                      positive or exceeds the available places
     * @throws IllegalStateException        if the event is past SCHEDULED, or the zone is not standing
     * @throws BusinessRuleViolationException if the event has no venue map
     */
    @Transactional
    public void removePlacesFromStandingZone(String token, int companyId, int eventId, int zoneId, int placesToRemove) {
        eventRepository.lockForUpdate(eventId);

        try {
            Event event = getAuthorizedEventForVenueEdit(token, companyId, eventId);
            event.removePlacesFromStandingZone(zoneId, placesToRemove, companyId);
            eventRepository.save(event);

            log.info("Removed {} places from standing zone {} in event {}", placesToRemove, zoneId, eventId);
        } finally {
            eventRepository.unlock(eventId);
        }
    }

    /**
     * UC-20: adds seats to a seated zone of an event. The zone and the map must stay within the
     * {@code catalog.venue-limits} seat caps.
     *
     * @param token      the member's session token
     * @param companyId  the company that owns the event
     * @param eventId    the event
     * @param zoneId     the seated zone
     * @param seatsToAdd the new seats, by label and position; see {@link Event#addSeatsToSeatedZone}
     * @throws InvalidTokenException        if {@code token} is not a live member token
     * @throws CompanyNotFoundException     if the company does not exist
     * @throws UnauthorizedActionException  if the member lacks {@code CONFIGURE_VENUE} in the company, or
     *                                      the event belongs to another company
     * @throws EventNotFoundException       if there is no such event
     * @throws IllegalArgumentException     if the map has no such zone, the zone is not a seated zone, a
     *                                      seat cap would be passed (the message is written for the user),
     *                                      the list is empty, or a label repeats one in the list or the
     *                                      zone
     * @throws IllegalStateException        if the event is past SCHEDULED, or a label is blank
     * @throws BusinessRuleViolationException if the event has no venue map
     */
    @Transactional
    public void addSeatsToSeatedZone(
            String token,
            int companyId,
            int eventId,
            int zoneId,
            List<VenueMapConfigDTO.SeatConfigDTO> seatsToAdd) {

        eventRepository.lockForUpdate(eventId);

        try {
            Event event = getAuthorizedEventForVenueEdit(token, companyId, eventId);
            requireSeatsWithinLimits(event, zoneId, seatsToAdd.size());
            event.addSeatsToSeatedZone(zoneId, toDomainSeats(seatsToAdd), companyId);
            eventRepository.save(event);

            log.info("Added {} seats to seated zone {} in event {}", seatsToAdd.size(), zoneId, eventId);
        } finally {
            eventRepository.unlock(eventId);
        }
    }

    /**
     * UC-20: removes seats from a seated zone of an event; all go or none do.
     *
     * @param token               the member's session token
     * @param companyId           the company that owns the event
     * @param eventId             the event
     * @param zoneId              the seated zone
     * @param seatLabelsToRemove  the labels to remove
     * @throws InvalidTokenException        if {@code token} is not a live member token
     * @throws CompanyNotFoundException     if the company does not exist
     * @throws UnauthorizedActionException  if the member lacks {@code CONFIGURE_VENUE} in the company, or
     *                                      the event belongs to another company
     * @throws EventNotFoundException       if there is no such event
     * @throws IllegalArgumentException     see {@link Event#removeSeatsFromSeatedZone}
     * @throws IllegalStateException        if the event is past SCHEDULED, or a named seat is not AVAILABLE
     * @throws BusinessRuleViolationException if the event has no venue map
     */
    @Transactional
    public void removeSeatsFromSeatedZone(
            String token,
            int companyId,
            int eventId,
            int zoneId,
            List<String> seatLabelsToRemove) {

        eventRepository.lockForUpdate(eventId);

        try {
            Event event = getAuthorizedEventForVenueEdit(token, companyId, eventId);
            event.removeSeatsFromSeatedZone(zoneId, seatLabelsToRemove, companyId);
            eventRepository.save(event);

            log.info("Removed {} seats from seated zone {} in event {}", seatLabelsToRemove.size(), zoneId, eventId);
        } finally {
            eventRepository.unlock(eventId);
        }
    }

    /**
     * UC-20: adds a row of seats to a seated zone of an event. Seat {@code i} (from 0) is labelled
     * {@code rowLabel} plus {@code firstSeatNumber + i}, and sits at {@code x = startX + i * seatSpacing},
     * {@code y}. The zone and the map must stay within the {@code catalog.venue-limits} seat caps.
     *
     * @param token           the member's session token
     * @param companyId       the company that owns the event
     * @param eventId         the event
     * @param zoneId          the seated zone
     * @param rowLabel        the row's label, such as {@code A}; non-blank; surrounding spaces are trimmed
     * @param firstSeatNumber the number of the first seat; positive
     * @param numberOfSeats   how many seats; positive
     * @param startX          the horizontal position of the first seat
     * @param y               the vertical position of every seat in the row
     * @param seatSpacing     the horizontal distance between neighbouring seats; positive
     * @throws InvalidTokenException        if {@code token} is not a live member token
     * @throws CompanyNotFoundException     if the company does not exist
     * @throws UnauthorizedActionException  if the member lacks {@code CONFIGURE_VENUE} in the company, or
     *                                      the event belongs to another company
     * @throws EventNotFoundException       if there is no such event
     * @throws IllegalArgumentException     if the map has no such zone, the zone is not a seated zone, a
     *                                      seat cap would be passed (the message is written for the user),
     *                                      an argument above is out of range, or a label repeats one the
     *                                      zone has
     * @throws IllegalStateException        if the event is past SCHEDULED
     * @throws BusinessRuleViolationException if the event has no venue map
     */
    @Transactional
    public void addSeatRowToSeatedZone(
            String token,
            int companyId,
            int eventId,
            int zoneId,
            String rowLabel,
            int firstSeatNumber,
            int numberOfSeats,
            double startX,
            double y,
            double seatSpacing) {

        eventRepository.lockForUpdate(eventId);

        try {
            Event event = getAuthorizedEventForVenueEdit(token, companyId, eventId);
            requireSeatsWithinLimits(event, zoneId, numberOfSeats);

            List<Seat> rowSeats = buildSeatRow(
                    rowLabel,
                    firstSeatNumber,
                    numberOfSeats,
                    startX,
                    y,
                    seatSpacing);
            event.addSeatsToSeatedZone(zoneId, rowSeats, companyId);
            eventRepository.save(event);

            log.info("Added row {} with {} seats to seated zone {} in event {}",
                    rowLabel, numberOfSeats, zoneId, eventId);
        } finally {
            eventRepository.unlock(eventId);
        }
    }

    /**
     * UC-20: removes a row of seats from a seated zone of an event. The caller names the row's seat
     * labels; this method does not look them up. All the labels go or none do.
     *
     * @param token                 the member's session token
     * @param companyId             the company that owns the event
     * @param eventId               the event
     * @param zoneId                the seated zone
     * @param rowSeatLabelsToRemove the labels of the row's seats
     * @throws InvalidTokenException        if {@code token} is not a live member token
     * @throws CompanyNotFoundException     if the company does not exist
     * @throws UnauthorizedActionException  if the member lacks {@code CONFIGURE_VENUE} in the company, or
     *                                      the event belongs to another company
     * @throws EventNotFoundException       if there is no such event
     * @throws IllegalArgumentException     see {@link Event#removeSeatsFromSeatedZone}
     * @throws IllegalStateException        if the event is past SCHEDULED, or a named seat is not AVAILABLE
     * @throws BusinessRuleViolationException if the event has no venue map
     */
    @Transactional
    public void removeSeatRowFromSeatedZone(
            String token,
            int companyId,
            int eventId,
            int zoneId,
            List<String> rowSeatLabelsToRemove) {

        eventRepository.lockForUpdate(eventId);

        try {
            Event event = getAuthorizedEventForVenueEdit(token, companyId, eventId);
            event.removeSeatsFromSeatedZone(zoneId, rowSeatLabelsToRemove, companyId);
            eventRepository.save(event);

            log.info("Removed row seats {} from seated zone {} in event {}",
                    rowSeatLabelsToRemove, zoneId, eventId);
        } finally {
            eventRepository.unlock(eventId);
        }
    }

    private Event getAuthorizedEventForVenueEdit(String token, int companyId, int eventId) {
        int userId = validateTokenAndGetUserId(token);
        User user = userRepository.getUserById(userId);

        try {
            companyRepository.getCompanyById(companyId);
        } catch (CompanyNotFoundException e) {
            log.warn("Request rejected: company not found. companyId={}", companyId);
            throw new CompanyNotFoundException();
        }

        companyMembershipService.requirePermissionInCompany(user.getUserId(),companyId, Permission.CONFIGURE_VENUE);

        Event event;
        try {
            event = eventRepository.findById(eventId);
        } catch (EventNotFoundException e) {
            log.warn("Event {} not found", eventId);
            throw new EventNotFoundException();
        }

        if (event.getCompanyId() != companyId) {
            throw new UnauthorizedActionException("act on an event that does not belong to this company");
        }

        return event;
    }

    private List<Seat> toDomainSeats(List<VenueMapConfigDTO.SeatConfigDTO> seatConfigs) {
        if (seatConfigs == null || seatConfigs.isEmpty()) {
            throw new IllegalArgumentException("Seats list must be non-empty");
        }

        return seatConfigs.stream()
                .map(seatConfig -> new Seat(seatConfig.label(), seatConfig.x(), seatConfig.y()))
                .toList();
    }

    // Checked on the request, before any domain object is built, so an oversized map costs no
    // allocation. A zone with no seats or no capacity is left to the existing checks further on.
    private void requireVenueWithinLimits(List<VenueMapConfigDTO.ZoneConfigDTO> zoneConfigs, int gridRows,
            int gridCols) {
        int maxGrid = venueLimits.maxGridSize();
        if (gridRows > maxGrid || gridCols > maxGrid) {
            throw new IllegalArgumentException(
                    "Venue grid " + gridRows + "x" + gridCols + " exceeds the " + maxGrid + "x" + maxGrid + " limit");
        }
        long gridCells = (long) gridRows * gridCols;
        if (zoneConfigs.size() > gridCells) {
            throw new IllegalArgumentException("A " + gridRows + "x" + gridCols + " venue grid holds at most "
                    + gridCells + " zones, not " + zoneConfigs.size());
        }
        long seatsOnMap = 0;
        for (VenueMapConfigDTO.ZoneConfigDTO zoneConfig : zoneConfigs) {
            requireZoneWithinLimits(zoneConfig);
            if (zoneConfig.seated()) {
                seatsOnMap += seatCount(zoneConfig);
            }
        }
        if (seatsOnMap > venueLimits.maxSeatsPerMap()) {
            throw new IllegalArgumentException("A venue map holds at most " + venueLimits.maxSeatsPerMap()
                    + " seats, not " + seatsOnMap);
        }
    }

    private void requireZoneWithinLimits(VenueMapConfigDTO.ZoneConfigDTO zoneConfig) {
        if (zoneConfig.seated()) {
            int seats = seatCount(zoneConfig);
            if (seats > venueLimits.maxSeatsPerZone()) {
                throw new IllegalArgumentException("A seated zone holds at most " + venueLimits.maxSeatsPerZone()
                        + " seats, not " + seats);
            }
        } else if (zoneConfig.capacity() != null && zoneConfig.capacity() > venueLimits.maxStandingCapacity()) {
            throw new IllegalArgumentException("A standing zone holds at most " + venueLimits.maxStandingCapacity()
                    + " places, not " + zoneConfig.capacity());
        }
    }

    // Runs before the domain adds the places, so a refused call leaves the zone unchanged. A missing
    // venue map or a seated zone is left to the domain, which refuses both.
    private void requireStandingCapacityWithinLimit(Event event, int zoneId, int placesToAdd) {
        VenueMap map = event.getVenueMap();
        if (map == null) {
            return;
        }
        InventoryZone zone = map.getZone(zoneId);
        long capacityAfter = (long) zone.getCapacity() + placesToAdd;
        if (zone.isStanding() && capacityAfter > venueLimits.maxStandingCapacity()) {
            throw new IllegalArgumentException("A standing zone holds at most " + venueLimits.maxStandingCapacity()
                    + " places, not " + capacityAfter);
        }
    }

    // Growing a seated zone seat by seat must stop where a whole map's zone would. Runs before the
    // domain adds the seats, so a refused call leaves the zone unchanged. A missing venue map or a
    // standing zone is left to the domain, which refuses both.
    private void requireSeatsWithinLimits(Event event, int zoneId, int seatsToAdd) {
        VenueMap map = event.getVenueMap();
        if (map == null) {
            return;
        }
        InventoryZone zone = map.getZone(zoneId);
        if (zone.isStanding()) {
            return;
        }
        long seatsAfter = (long) zone.getCapacity() + seatsToAdd;
        if (seatsAfter > venueLimits.maxSeatsPerZone()) {
            throw new IllegalArgumentException("A seated zone holds at most " + venueLimits.maxSeatsPerZone()
                    + " seats, not " + seatsAfter);
        }
        requireMapSeatsWithinLimit(event, seatsToAdd);
    }

    // configureVenueMap allows no more zones than grid cells; adding zones one at a time must stop there
    // too, or repeated calls would grow a map past every per-zone cap combined.
    private void requireFreeGridCell(Event event) {
        VenueMap map = event.getVenueMap();
        if (map == null) {
            return;
        }
        long gridCells = (long) map.getGridRows() * map.getGridCols();
        if (map.getInventoryZones().size() + 1 > gridCells) {
            throw new IllegalArgumentException("A " + map.getGridRows() + "x" + map.getGridCols()
                    + " venue grid holds at most " + gridCells + " zones");
        }
    }

    // The map-wide seat cap, for the calls that add seats to an existing map one zone or row at a time.
    private void requireMapSeatsWithinLimit(Event event, int seatsToAdd) {
        VenueMap map = event.getVenueMap();
        if (map == null) {
            return;
        }
        long seatsAfter = seatsToAdd;
        for (InventoryZone zone : map.getInventoryZones()) {
            if (!zone.isStanding()) {
                seatsAfter += zone.getCapacity();
            }
        }
        if (seatsAfter > venueLimits.maxSeatsPerMap()) {
            throw new IllegalArgumentException("A venue map holds at most " + venueLimits.maxSeatsPerMap()
                    + " seats, not " + seatsAfter);
        }
    }

    private static int seatCount(VenueMapConfigDTO.ZoneConfigDTO zoneConfig) {
        return zoneConfig.seats() == null ? 0 : zoneConfig.seats().size();
    }

    private List<Seat> buildSeatRow(
            String rowLabel,
            int firstSeatNumber,
            int numberOfSeats,
            double startX,
            double y,
            double seatSpacing) {

        if (rowLabel == null || rowLabel.isBlank()) {
            throw new IllegalArgumentException("Row label must be non-blank");
        }

        if (firstSeatNumber <= 0) {
            throw new IllegalArgumentException("First seat number must be positive");
        }

        if (numberOfSeats <= 0) {
            throw new IllegalArgumentException("Number of seats must be positive");
        }

        if (seatSpacing <= 0) {
            throw new IllegalArgumentException("Seat spacing must be positive");
        }

        List<Seat> rowSeats = new ArrayList<>();

        for (int i = 0; i < numberOfSeats; i++) {
            String seatLabel = rowLabel.trim() + (firstSeatNumber + i);
            rowSeats.add(new Seat(seatLabel, startX + (i * seatSpacing), y));
        }

        return rowSeats;
    }

    /**
     * Returns an event's full detail for the owner-side editing pages. Any active member of the event's
     * company may load it; the pages that call it gate themselves by capability.
     *
     * @param token   the member's session token
     * @param eventId the event
     * @return the event's detail, with its description
     * @throws InvalidTokenException        if {@code token} is not a live member token
     * @throws EventNotFoundException       if there is no such event
     * @throws UnauthorizedActionException  if the member is not an active member of the event's company
     */
    @Transactional(readOnly = true)
    public EventDetailDTO getEventDetail(String token, int eventId) {
        int userId = validateTokenAndGetUserId(token);

        Event event = eventRepository.findById(eventId);

        ProductionCompany company = companyRepository.getCompanyById(event.getCompanyId());

        User user = userRepository.getUserById(userId);
        // Read-only detail: any active member may load it. The pages that reach this (event-detail
        // editor, venue editor) are each route-gated by their own capability, so this serves both a
        // MANAGE_INVENTORY metadata editor and a CONFIGURE_VENUE venue editor.
        companyMembershipService.requireMemberInCompany(user.getUserId(),event.getCompanyId());

        return new EventMapper().toEventDetailDTO(event, company.getName());
    }

    /**
     * UC-19: opens an event's sales, moving it from SCHEDULED to ON_SALE; publishing an event that is
     * already ON_SALE changes nothing. The transition and its venue-map, show-date and invariant guards
     * live in {@link Event#transitionToOnSale}; this method adds authentication, ownership and locking.
     *
     * @param token     the member's session token
     * @param companyId the company that owns the event
     * @param eventId   the event
     * @throws InvalidTokenException        if {@code token} is not a live member token
     * @throws EventNotFoundException       if there is no such event
     * @throws UnauthorizedActionException  if the event belongs to another company, or the member lacks
     *                                      {@code MANAGE_INVENTORY} in the company
     * @throws InvalidStateTransitionException if the event cannot go on sale; see
     *                                      {@link Event#transitionToOnSale}
     */
    @Transactional
    public void publishEvent(String token, int companyId, int eventId) {
        int userId = validateTokenAndGetUserId(token);

        eventRepository.lockForUpdate(eventId);
        try {
            Event event = eventRepository.findById(eventId);

            if (event.getCompanyId() != companyId) {
                throw new UnauthorizedActionException("act on an event that does not belong to this company");
            }

            User user = userRepository.getUserById(userId);
            companyMembershipService.requirePermissionInCompany(user.getUserId(),companyId, Permission.MANAGE_INVENTORY);

            event.transitionToOnSale(); // SCHEDULED -> ON_SALE (idempotent if already ON_SALE)
            eventRepository.save(event);

            log.info("Event {} published (ON_SALE) by user {}", eventId, userId);
        } finally {
            eventRepository.unlock(eventId);
        }
    }

    /**
     * Permanently removes a CANCELED event that has no sales history. A cancelled event is otherwise kept
     * for history and reporting.
     *
     * @param token   the member's session token
     * @param eventId the event
     * @throws InvalidTokenException           if {@code token} is not a live member token
     * @throws EventNotFoundException          if there is no such event
     * @throws UnauthorizedActionException     if the member lacks {@code MANAGE_INVENTORY} in the event's
     *                                         company
     * @throws InvalidStateTransitionException if the event is not CANCELED
     * @throws BusinessRuleViolationException  if the event has sales history, since deleting it would
     *                                         orphan its receipts and tickets
     */
    @Transactional
    public void deleteEvent(String token, int eventId) {
        int userId = validateTokenAndGetUserId(token);
        eventRepository.lockForUpdate(eventId);
        try {
            Event event = eventRepository.findById(eventId);
            User user = userRepository.getUserById(userId);
            companyMembershipService.requirePermissionInCompany(user.getUserId(),event.getCompanyId(), Permission.MANAGE_INVENTORY);
            if (event.getStatus() != EventStatus.CANCELED) {
                throw new InvalidStateTransitionException("Only CANCELED events can be deleted");
            }
            // Soft-cancel keeps events "for historical and reporting purposes": refuse to
            // permanently delete one that still has purchase history, which would orphan its
            // OrderReceipt/Ticket records (referenced by eventId, no cascade). The sales-history
            // check goes through a catalog port (sales-implemented) so catalog imports no sales type.
            if (eventSalesHistoryPort.hasSalesHistory(eventId)) {
                throw new BusinessRuleViolationException("Can't delete an event with sales history");
            }
            eventRepository.delete(eventId);
            log.info("Event {} deleted by user {}", eventId, userId);
        } finally {
            eventRepository.unlock(eventId);
        }
    }

    /**
     * Moves an event to SCHEDULED or ON_SALE by hand. Cancelling goes through
     * {@link #cancelEventAndRefund}, and the other states are reached by the system.
     *
     * @param token        the member's session token
     * @param eventId      the event
     * @param targetStatus {@link EventStatus#SCHEDULED} or {@link EventStatus#ON_SALE}
     * @throws InvalidTokenException           if {@code token} is not a live member token
     * @throws EventNotFoundException          if there is no such event
     * @throws UnauthorizedActionException     if the member lacks {@code MANAGE_INVENTORY} in the event's
     *                                         company
     * @throws InvalidStateTransitionException if {@code targetStatus} is any other status, or the event
     *                                         cannot make that transition
     */
    @Transactional
    public void changeEventStatus(String token, int eventId, EventStatus targetStatus) {
        int userId = validateTokenAndGetUserId(token);
        eventRepository.lockForUpdate(eventId);
        try {
            Event event = eventRepository.findById(eventId);
            User user = userRepository.getUserById(userId);
            companyMembershipService.requirePermissionInCompany(user.getUserId(),event.getCompanyId(), Permission.MANAGE_INVENTORY);
            switch (targetStatus) {
                case SCHEDULED -> event.transitionToScheduled();
                case ON_SALE -> event.transitionToOnSale();
                default -> throw new InvalidStateTransitionException("Cannot manually set status to " + targetStatus);
            }
            eventRepository.save(event);
            log.info("Event {} status changed to {} by user {}", eventId, targetStatus, userId);
        } finally {
            eventRepository.unlock(eventId);
        }
    }

    /**
     * UC-19: cancels an event softly, keeping it for history and reporting while it blocks future
     * purchases. Catalog owns only the lifecycle transition here: it cancels the event and publishes an
     * {@link EventCancelledEvent}. The sales context's {@code EventCancellationRefundListener} consumes
     * it, synchronously, and runs the refund, ticket-void and holder-notification flow, so catalog
     * touches no sales type.
     *
     * <p>Deliberately not {@code @Transactional}: the CANCELED status must commit even when a refund
     * fails, and the sales side refunds each receipt in its own short transactions with the gateway call
     * outside them (V3-TX-02, ADR-001). A failure the listener raises propagates from this method, after
     * the status is committed. Calling it again on a CANCELED event publishes the event again, which
     * resumes the refunds a failure interrupted (#20).
     *
     * @param token   the member's session token
     * @param eventId the event
     * @throws InvalidTokenException       if {@code token} is not a live member token
     * @throws EventNotFoundException      if there is no such event
     * @throws UnauthorizedActionException if the member lacks {@code MANAGE_INVENTORY} in the event's
     *                                     company
     * @throws IllegalStateException       if the event is COMPLETED
     * @throws com.ticketing.system.shared.exception.RefundFailedException if a refund fails; the event
     *     stays CANCELED and calling again resumes
     */
    public void cancelEventAndRefund(String token, int eventId) {
        int ownerId = validateTokenAndGetUserId(token);
        // We lock the event for update to prevent concurrent modifications during the
        // cancellation process. This ensures that we have a consistent view of the event's state.
        eventRepository.lockForUpdate(eventId);

        // Only a missing event is logged here. Any other failure, a RefundFailedException from the sales
        // listener included, propagates unlogged here: LoggingAspect logs it at WARN, and the sales refund
        // service logs its own refund failures. The presenter shows a DomainException's message unlogged.
        try {
            Event event = eventRepository.findById(eventId); // throws if not found, which is what we want here.
            User user = userRepository.getUserById(ownerId);
            companyMembershipService.requirePermissionInCompany(user.getUserId(),event.getCompanyId(), Permission.MANAGE_INVENTORY);

            if (event.getStatus() != EventStatus.CANCELED) {
                // Soft cancel: keep the event for history/reporting while blocking future purchases.
                event.transitionToCanceled("Event canceled by owner");
                eventRepository.save(event);
            }

            // Hand the sales-side work (refunds, ticket voiding, holder notifications) to the sales
            // context via a domain event, on every call: for an already-cancelled event this resumes
            // the refunds still pending. The synchronous listener runs inline, so a
            // RefundFailedException it raises surfaces here and the caller can retry.
            eventPublisher.publishEvent(new EventCancelledEvent(eventId, event.getName()));

            log.info("Event {} canceled and cancellation event published", eventId);

        } catch (EventNotFoundException e) {
            log.warn("Event {} not found for cancellation", eventId);
            throw e;
        } finally {
            eventRepository.unlock(eventId);
        }
    }

    /**
     * UC-21: sets or replaces the event-level purchase policy. It replaces the event's own policy with
     * one built from the DTO and stores only that: the company's policy is combined with it each time a
     * purchase is checked ({@link Event#validateEffectivePolicy}). No discount is set here: an event's
     * discount stays the 0% {@link #addEvent} gives it, and whether it should be settable is
     * NaimElijah/Ticketeer#259.
     *
     * <p>As for any write to an existing event, the event is locked for the update.
     *
     * @param token  the member's session token
     * @param config the event, its company and the new policy
     * @throws InvalidTokenException        if {@code token} is not a live member token
     * @throws IllegalArgumentException     if {@code config} is {@code null}, or its purchase policy DTO is
     *                                      malformed
     * @throws CompanyNotFoundException     if the company does not exist
     * @throws UnauthorizedActionException  if the member lacks {@code EDIT_POLICIES} in the company, or
     *                                      the event belongs to another company
     * @throws EventNotFoundException       if there is no such event
     */
    @Transactional
    public void setEventPolicies(String token, EventPolicyConfigDTO config) {
        if (!sessionManager.validateToken(token)) {
            throw new InvalidTokenException();
        }

        if (config == null) {
            throw new IllegalArgumentException("Event policy config cannot be null");
        }

        eventRepository.lockForUpdate(config.eventId());

        try {
            int userId = sessionManager.extractUserId(token);

            companyRepository.getCompanyById(config.companyId());

            User user = userRepository.getUserById(userId);
            if (user == null) {
                throw new UserNotFoundException();
            }
            companyMembershipService.requirePermissionInCompany(user.getUserId(),config.companyId(), Permission.EDIT_POLICIES);

            Event event = eventRepository.findById(config.eventId());

            if (event.getCompanyId() != config.companyId()) {
                throw new UnauthorizedActionException("act on an event that does not belong to this company");
            }

           PurchasePolicy eventSpecificPurchasePolicy = PurchasePolicyDtoMapper.toPolicy(config.purchasePolicy());

            event.setPurchasePolicy(eventSpecificPurchasePolicy);

            eventRepository.save(event);

            log.info("Purchase policy for event {} was updated by user {}", event.getId(), userId);
        } finally {
            eventRepository.unlock(config.eventId());
        }
    }

    private int validateTokenAndGetUserId(String token) {
        if (!sessionManager.validateToken(token)) {
            log.warn("Invalid token provided");
            throw new InvalidTokenException();
        }
        return sessionManager.extractUserId(token);
    }

    /**
     * UC-21: returns an event's own purchase policy, not combined with the company's.
     *
     * @param token     the member's session token
     * @param companyId the company that owns the event
     * @param eventId   the event
     * @return the policy as a DTO
     * @throws InvalidTokenException        if {@code token} is not a live member token
     * @throws CompanyNotFoundException     if the company does not exist
     * @throws UnauthorizedActionException  if the member lacks {@code EDIT_POLICIES} in the company, or
     *                                      the event belongs to another company
     * @throws EventNotFoundException       if there is no such event
     */
    @Transactional(readOnly = true)
    public PurchasePolicyDTO getEventPurchasePolicy(String token, int companyId, int eventId) {
        if (!sessionManager.validateToken(token))
            throw new InvalidTokenException();
        int userId = sessionManager.extractUserId(token);
        companyRepository.getCompanyById(companyId);
        User user = userRepository.getUserById(userId);
        if (user == null)
            throw new UserNotFoundException();
        companyMembershipService.requirePermissionInCompany(user.getUserId(),companyId, Permission.EDIT_POLICIES);
        Event event = eventRepository.findById(eventId);
        if (event.getCompanyId() != companyId)
            throw new UnauthorizedActionException("act on an event that does not belong to this company");
        return PurchasePolicyDtoMapper.toDto(event.getPurchasePolicy());
    }
}
