package com.ticketing.system.Core.Application.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
// Owner / Manager-side write service for the Event aggregate and its lifecycle.
// UC-19 (Manage Event Catalog), UC-20 (Configure Venue Map & Inventory), UC-21 (Configure Policies).
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.Core.Application.dto.RefundResultDTO;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.CompanyNotFoundException;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.shared.exception.UserNotFoundException;
import com.ticketing.system.shared.exception.RefundFailedException;
import com.ticketing.system.Core.Domain.orders.TransactionRecord;
import com.ticketing.system.Core.Application.dto.EventCreationDTO;
import com.ticketing.system.Core.Application.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.Core.Application.dto.EventDetailDTO;
import com.ticketing.system.Core.Application.dto.EventPolicyConfigDTO;
import com.ticketing.system.Core.Application.dto.EventUpdateDTO;
import com.ticketing.system.Core.Application.dto.PurchasePolicyDTO;
import com.ticketing.system.Core.Application.dto.GridPlacementDTO;
import com.ticketing.system.Core.Application.dto.VenueLayoutDTO;
import com.ticketing.system.Core.Application.dtoMappers.EventMapper;
import com.ticketing.system.Core.Application.dto.VenueMapConfigDTO;
import com.ticketing.system.Core.Application.dto.ZoneDetailDTO;
import com.ticketing.system.Core.Application.interfaces.IPaymentGateway;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.Core.Application.interfaces.INotificationService;
import com.ticketing.system.Core.Domain.Tickets.ITicketRepository;
import com.ticketing.system.Core.Domain.Tickets.Ticket;
import com.ticketing.system.Core.Domain.Tickets.TicketStatus;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.Core.Domain.events.Event;
import com.ticketing.system.Core.Domain.events.EventStatus;
import com.ticketing.system.Core.Domain.events.EventCategory;
import com.ticketing.system.Core.Domain.events.IEventRepository;
import com.ticketing.system.Core.Domain.events.Location;
import com.ticketing.system.Core.Domain.events.InventoryZone;
import com.ticketing.system.Core.Domain.events.StandingZone;
import com.ticketing.system.Core.Domain.events.VenueMap;
import com.ticketing.system.Core.Domain.orders.IOrderReceiptRepository;
import com.ticketing.system.Core.Domain.orders.OrderReceipt;
import com.ticketing.system.Core.Domain.orders.ReceiptLine;
import com.ticketing.system.Core.Domain.events.DiscountPolicy;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.Core.Domain.users.Permission;
import com.ticketing.system.Core.Domain.events.Seat;
import com.ticketing.system.Core.Domain.events.SeatedZone;
import com.ticketing.system.Core.Domain.events.ShowDate;
import com.ticketing.system.Core.Domain.policies.purchase.NoPurchasePolicy;
import com.ticketing.system.Core.Domain.policies.purchase.OrPurchasePolicy;
import com.ticketing.system.Core.Domain.policies.purchase.PurchasePolicy;
import com.ticketing.system.Core.Domain.policies.purchase.AgePurchasePolicy;
import com.ticketing.system.Core.Domain.policies.purchase.AndPurchasePolicy;
import com.ticketing.system.Core.Domain.policies.purchase.MaxTicketsPurchasePolicy;
import com.ticketing.system.Core.Domain.policies.purchase.MinTicketsPurchasePolicy;

@Service
@Slf4j
public class EventManagementService {

    private final IEventRepository eventRepository;
    private final ProductionCompanyRepository companyRepository;
    private final ITicketRepository ticketRepository;
    private final SessionManager sessionManager;
    private final IOrderReceiptRepository orderReceiptRepository;
    private final IPaymentGateway paymentGateway;
    private final UserRepository userRepository;
    private final INotificationService notificationService;
    private int currentVenueMapIdCounter;

    public EventManagementService(
            IEventRepository eventRepository,
            ProductionCompanyRepository companyRepository,
            ITicketRepository ticketRepository,
            SessionManager sessionManager,
            IOrderReceiptRepository orderReceiptRepository,
            IPaymentGateway paymentGateway,
            UserRepository userRepository,
            INotificationService notificationService) {
        this.eventRepository = eventRepository;
        this.companyRepository = companyRepository;
        this.ticketRepository = ticketRepository;
        this.sessionManager = sessionManager;
        this.orderReceiptRepository = orderReceiptRepository;
        this.paymentGateway = paymentGateway;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.currentVenueMapIdCounter = 0; // Initialize the venue map ID counter, change the counter to be internal but
                                           // here for now.
    }

    // Flow:

    // addEvent adds an event in DRAFT state; venue map and inventory configuration
    // come later in UC-20.

    // Note: we could combine addEvent and configureVenueMap into a single UC-19
    // method that takes a more complex DTO, but separating them allows for a
    // cleaner separation of concerns and
    // more focused validation (e.g. addEvent doesn't need to worry about venue map
    // details at all).

    // configureVenueMap is a separate method that can be called multiple times to
    // update the venue map and inventory zones *before* the event goes live.
    // It also allows for a more iterative setup process where the owner/manager can
    // first create the event with basic details and then configure the venue map in
    // a second step.

    // UC-19 — Owner adds an Event in DRAFT state.
    @Transactional
    public EventDetailDTO addEvent(String token, EventCreationDTO request) {
        int ownerId = validateTokenAndGetUserId(token);

        ProductionCompany company = null;
        // TODO: see about all the other exceptions throws handlings/catches.
        try {
            company = companyRepository.getCompanyById(request.companyId());
        } catch (RuntimeException e) {
            log.error("Error - company not found: {}, {}", request.companyId(), e.getMessage());
            throw new CompanyNotFoundException();
        }

        User user = userRepository.getUserById(ownerId);
        user.requirePermissionInCompany(request.companyId(), Permission.MANAGE_INVENTORY);

        int newEventId = eventRepository.nextId();
        VenueMap venueMap = new VenueMap(eventRepository.nextVenueMapId(), request.location(), List.of());
        // ! Note: Discount policy is currently not in the implementation plan so just
        // put as 0 discount for every event here
        // not doing discount automatically without the ability to change this from the
        // outside right now.
        DiscountPolicy discountPolicy = new DiscountPolicy(0);

        
        PurchasePolicy eventSpecificPurchasePolicy = buildPurchasePolicyFromDTO(request.purchasePolicy());
       
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
                eventSpecificPurchasePolicy ,
                discountPolicy);
        eventRepository.save(newEvent);

        log.info("Event {} created successfully with ID {}", request.name(), newEventId);
        return new EventMapper().toEventDetailDTO(newEvent, company.getName());
    }

    // II.4.1.1 — Owner lists all events under their company.
    @Transactional(readOnly = true)
    public List<EventDetailDTO> listEventsForCompany(String token, int companyId) {
        return listEventsForCompany(token, companyId, CatalogSearchFiltersDTO.empty());
    }

    // II.4.1.1 — Owner lists their company's events narrowed by the catalogue filters (company-rating
    // filters don't apply to a single company, so they're stripped).
    @Transactional(readOnly = true)
    public List<EventDetailDTO> listEventsForCompany(String token, int companyId, CatalogSearchFiltersDTO filters) {
        int userId = validateTokenAndGetUserId(token);
        User user = userRepository.getUserById(userId);
        if (user == null) {
            throw new UserNotFoundException();
        }

        ProductionCompany company = companyRepository.getCompanyById(companyId);
        if (company == null) {
            throw new CompanyNotFoundException();
        }

        // The events list is a read-only management hub. Any active member of the company may see
        // it (so e.g. a venue/sales manager can reach the per-event venue editor); the per-event
        // mutations (configure venue, edit details, policies, cancel) stay gated by their own
        // permission at their own service entry points.
        user.requireMemberInCompany(companyId);

        EventMapper mapper = new EventMapper();
        return eventRepository.searchByCompanyAll(companyId, filters.withoutCompanyRating()).stream()
            .map(e -> mapper.toEventDetailDTO(e, company.getName()))
            .toList();
    }

    @Transactional(readOnly = true)
    public EventDetailDTO getEvent(String token, int eventId) {
        int userId = validateTokenAndGetUserId(token);
        Event event = eventRepository.findById(eventId);
        if (event == null) {
            throw new EventNotFoundException();
        }
        User user = userRepository.getUserById(userId);
        if (user == null) {
            throw new UserNotFoundException();
        }
        user.requirePermissionInCompany(event.getCompanyId(), Permission.MANAGE_INVENTORY);
        ProductionCompany company = companyRepository.getCompanyById(event.getCompanyId());
        if (company == null) {
            throw new CompanyNotFoundException();
        }

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
            event.getArtistsNames(),
            EventMapper.minZonePrice(event)
            );
    }

    



    // II.4.2.3 — Read back the current zone states from the domain so the
    // editor reflects real-time inventory (capacity consumed by sales, etc.).
    @Transactional(readOnly = true)
    public VenueLayoutDTO getEventZones(String token, int eventId) {
        int userId = validateTokenAndGetUserId(token);
        User user = userRepository.getUserById(userId);
        if (user == null) {
            throw new UserNotFoundException();
        }
        Event event = eventRepository.findById(eventId);
        if (event == null) {
            throw new EventNotFoundException();
        }
        user.requirePermissionInCompany(event.getCompanyId(), Permission.CONFIGURE_VENUE);

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

    // configureVenueMap is a separate method that can be called multiple times to
    // update the venue map and inventory zones *before* the event goes live.
    // It also allows for a more iterative setup process where the owner/manager can
    // first create the event with basic details and then configure the venue map in
    // a second step.
    @Transactional
    public void configureVenueMap(String token, int companyId, VenueMapConfigDTO config) {
        int eventId = Integer.parseInt(config.eventId());

        eventRepository.lockForUpdate(eventId);
        try {
            int userId = validateTokenAndGetUserId(token);

            log.info("Configuring venue map for company {}, event {}, by user {}", companyId, config.eventId(), userId);

            User user = userRepository.getUserById(userId);
            user.requirePermissionInCompany(companyId, Permission.CONFIGURE_VENUE);

            Event event = eventRepository.findById(eventId);

            if (event.getCompanyId() != companyId) {
                throw new UnauthorizedActionException("act on an event that does not belong to this company");
            }

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

            int gridRows = config.gridRows() > 0 ? config.gridRows() : VenueMap.DEFAULT_GRID_ROWS;
            int gridCols = config.gridCols() > 0 ? config.gridCols() : VenueMap.DEFAULT_GRID_COLS;
            VenueMap venueMap = new VenueMap(
                    event.getVenueMap() != null ? event.getVenueMap().getId() : eventRepository.nextVenueMapId(),
                    event.getVenueMap() != null ? event.getVenueMap().getLocation() : null,
                    zones, gridRows, gridCols);

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

    // UC-19 — partial update; immutability rules enforced inside
    // Event.editDetails().
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
            user.requirePermissionInCompany(event.getCompanyId(), Permission.MANAGE_INVENTORY);

            EventCategory newCategory = null;
            if (update.category() != null && !update.category().isBlank()) {
                try {
                    newCategory = EventCategory.valueOf(update.category().trim().toUpperCase());
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

            event.editDetails(update.name(), update.description(), newCategory, newLocation, newShowDates);
            eventRepository.save(event);

            log.info("Event {} details updated by user {}", eventId, userId);
        } finally {
            eventRepository.unlock(eventId);
        }
    }

    @Transactional
    public int addInventoryZone(String token, int companyId, int eventId, VenueMapConfigDTO.ZoneConfigDTO zoneConfig) {

        eventRepository.lockForUpdate(eventId);

        try {
            if (zoneConfig == null) {
                throw new IllegalArgumentException("Zone config cannot be null");
            }

            Event event = getAuthorizedEventForVenueEdit(token, companyId, eventId);

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

    // UC-20 — Owner/Manager configures venue map and inventory zones.

    // addPlacesToStandingZone is a helper function that allows the owner/manager to
    // add more places to a standing zone.
    @Transactional
    public void addPlacesToStandingZone(String token, int companyId, int eventId, int zoneId, int placesToAdd) {
        eventRepository.lockForUpdate(eventId);

        try {
            Event event = getAuthorizedEventForVenueEdit(token, companyId, eventId);
            event.addPlacesToStandingZone(zoneId, placesToAdd, companyId);
            eventRepository.save(event);

            log.info("Added {} places to standing zone {} in event {}", placesToAdd, zoneId, eventId);
        } finally {
            eventRepository.unlock(eventId);
        }
    }

    // removePlacesFromStandingZone is a helper function that allows the
    // owner/manager to remove a specified number of places from a standing zone.
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

    // addSeatsToSeatedZone is a helper function that allows the owner/manager to
    // add specific seats to a seated zone.
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
            event.addSeatsToSeatedZone(zoneId, toDomainSeats(seatsToAdd), companyId);
            eventRepository.save(event);

            log.info("Added {} seats to seated zone {} in event {}", seatsToAdd.size(), zoneId, eventId);
        } finally {
            eventRepository.unlock(eventId);
        }
    }

    // removeSeatsFromSeatedZone is a helper function that allows the owner/manager
    // to remove specific seats from a seated zone.
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

    // addSeatRowToSeatedZone is a helper function that allows the owner/manager to
    // add an entire row of seats to a seated zone.
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

    // *
    // * removeSeatRowFromSeatedZone is a helper function that allows the
    // owner/manager to remove an entire row of seats from a seated zone.
    // * It takes the row label and constructs the list of seat labels to remove
    // based on the existing seat layout in the zone.
    // * This ensures that all seats in the specified row are removed consistently.
    // */
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

    // getAuthorizedEventForVenueEdit is a helper function that validates the token,
    // checks user permissions,
    // and retrieves the event for venue editing. It throws exceptions if any
    // validation fails.
    private Event getAuthorizedEventForVenueEdit(String token, int companyId, int eventId) {
        int userId = validateTokenAndGetUserId(token);
        User user = userRepository.getUserById(userId);

        try {
            companyRepository.getCompanyById(companyId);
        } catch (RuntimeException e) {
            log.error("Error - company not found: {}, {}", companyId, e.getMessage());
            throw new CompanyNotFoundException();
        }

        user.requirePermissionInCompany(companyId, Permission.CONFIGURE_VENUE);

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

    // to-DomainSeats is a helper function to convert a list of SeatConfigDTO
    // objects to a list of Seat domain objects.
    private List<Seat> toDomainSeats(List<VenueMapConfigDTO.SeatConfigDTO> seatConfigs) {
        if (seatConfigs == null || seatConfigs.isEmpty()) {
            throw new IllegalArgumentException("Seats list must be non-empty");
        }

        return seatConfigs.stream()
                .map(seatConfig -> new Seat(seatConfig.label(), seatConfig.x(), seatConfig.y()))
                .toList();
    }

    // buildSeatRow is a helper function to create a list of Seat objects for a row
    // in a seated zone.
    // It validates the input parameters and constructs the seats based on the
    // provided starting position and spacing.
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

    // Detail view for owner-side editing pages.
    @Transactional(readOnly = true)
    public EventDetailDTO getEventDetail(String token, int eventId) {
        int userId = validateTokenAndGetUserId(token);

        Event event = eventRepository.findById(eventId);

        ProductionCompany company = companyRepository.getCompanyById(event.getCompanyId());

        User user = userRepository.getUserById(userId);
        // Read-only detail: any active member may load it. The pages that reach this (event-detail
        // editor, venue editor) are each route-gated by their own capability, so this serves both a
        // MANAGE_INVENTORY metadata editor and a CONFIGURE_VENUE venue editor.
        user.requireMemberInCompany(event.getCompanyId());

        return new EventMapper().toEventDetailDTO(event, company.getName());
    }

    // UC-19 — owner opens an event's sales: SCHEDULED -> ON_SALE.
    // The actual transition (and its venue-map/show-date/invariant guards) lives in
    // Event.transitionToOnSale(); this method enforces auth, ownership, and
    // locking.
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
            user.requirePermissionInCompany(companyId, Permission.MANAGE_INVENTORY);

            event.transitionToOnSale(); // SCHEDULED -> ON_SALE (idempotent if already ON_SALE)
            eventRepository.save(event);

            log.info("Event {} published (ON_SALE) by user {}", eventId, userId);
        } finally {
            eventRepository.unlock(eventId);
        }
    }

    // Permanently removes a CANCELED event. Only callable by a user with MANAGE_INVENTORY.
    public void deleteEvent(String token, int eventId) {
        int userId = validateTokenAndGetUserId(token);
        eventRepository.lockForUpdate(eventId);
        try {
            Event event = eventRepository.findById(eventId);
            User user = userRepository.getUserById(userId);
            user.requirePermissionInCompany(event.getCompanyId(), Permission.MANAGE_INVENTORY);
            if (event.getStatus() != EventStatus.CANCELED) {
                throw new InvalidStateTransitionException("Only CANCELED events can be deleted");
            }
            // Soft-cancel keeps events "for historical and reporting purposes": refuse to
            // permanently delete one that still has purchase history, which would orphan its
            // OrderReceipt/Ticket records (referenced by eventId, no cascade).
            if (!orderReceiptRepository.findByEventId(eventId).isEmpty()) {
                throw new BusinessRuleViolationException("Can't delete an event with sales history");
            }
            eventRepository.delete(eventId);
            log.info("Event {} deleted by user {}", eventId, userId);
        } finally {
            eventRepository.unlock(eventId);
        }
    }

    // Owner/Manager changes an event's status to a target state (non-cancel transitions only).
    // Cancel with refund pipeline uses cancelEventAndRefund.
    public void changeEventStatus(String token, int eventId, EventStatus targetStatus) {
        int userId = validateTokenAndGetUserId(token);
        eventRepository.lockForUpdate(eventId);
        try {
            Event event = eventRepository.findById(eventId);
            User user = userRepository.getUserById(userId);
            user.requirePermissionInCompany(event.getCompanyId(), Permission.MANAGE_INVENTORY);
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

    // UC-19 — soft cancel; fires EventCancelled domain event for UC-4 refund
    // pipeline.
    // Intentionally NOT @Transactional: this calls the external payment gateway (refund) mid-flow,
    // so its transaction boundary is restructured separately (externals outside the tx) in V3-TX-02.
    public void cancelEventAndRefund(String token, int eventId) {
        int ownerId = validateTokenAndGetUserId(token);
        // We lock the event for update to prevent concurrent modifications during the
        // cancellation and refund process. This ensures that we have a consistent view
        // of the event's state
        eventRepository.lockForUpdate(eventId);

        try {
            Event event = eventRepository.findById(eventId); // throws if not found, which is what we want here.
            User user = userRepository.getUserById(ownerId);
            user.requirePermissionInCompany(event.getCompanyId(), Permission.MANAGE_INVENTORY);

            if (event.getStatus() == EventStatus.CANCELED) {
                return;
            }

            List<OrderReceipt> orderReceipts = orderReceiptRepository.findByEventId(eventId);
            // For each receipt, we calculate the total refund amount for the lines related
            // to the canceled event, call the payment gateway to process the refund,
            // validate the refund result, and then update our domain state accordingly
            // (marking the receipt as refunded and updating ticket statuses). We also
            // handle various edge cases and potential errors along the way to ensure a
            // robust refund process.
            for (OrderReceipt receipt : orderReceipts) {
                double totalRefundForReceipt = receipt.getReceiptLines().stream()
                        .filter(line -> line.getEventId() == eventId)
                        .mapToDouble(ReceiptLine::getPriceAtReservation)
                        .sum();

                if (totalRefundForReceipt <= 0) {
                    continue;
                }
                // Extract the payment transaction ID from the receipt's transaction records.
                // This is necessary to tell the payment gateway which transaction we want to
                // refund. If we can't find a valid payment transaction ID, we throw an
                // exception and skip this receipt since we don't want to risk refunding without
                // a valid reference to the original charge.
                int paymentTransactionId = receipt.getPaymentTransactionId()
                        .orElseThrow(() -> new RefundFailedException(
                                receipt.getId(),
                                "receipt does not contain a payment transaction"));
                // Call the payment gateway to process the refund. This is a critical step that
                // must succeed before we make any changes to our domain state (e.g. marking
                // tickets as refunded or canceling the event) since we want to avoid
                // inconsistencies where we think we've refunded a customer but the payment
                // gateway disagrees.
                RefundResultDTO refundResult = paymentGateway.refund(
                        paymentTransactionId,
                        totalRefundForReceipt);
                // Validate the refund result before proceeding. If the refund failed for some
                // reason, we want to throw an exception and avoid making any changes to our
                // domain state (e.g. marking tickets as refunded or canceling the event) since
                // that could lead to inconsistencies.
                validateRefundResult(receipt.getId(), totalRefundForReceipt, refundResult);

                // If we reach this point, the refund was successful and we can update our
                // domain state accordingly.
                receipt.markRefunded(TransactionRecord.refund(
                        refundResult.refundTransactionId(),
                        paymentGateway.getId(),
                        refundResult.totalRefunded(),
                        receipt.getPaymentCurrency(),
                        refundResult.refundedAt()));

                orderReceiptRepository.save(receipt);
            }
            // After processing refunds for all receipts, we need to update the status of
            // all tickets for the event. For simplicity, we assume that if a ticket was
            // PAID or ISSUED, it should be marked as REFUNDED; otherwise, it can be marked
            // as VOIDED. In a real system, we might want to handle this more robustly (e.g.
            // consider different ticket statuses, handle partial refunds, etc.).
            List<Ticket> tickets = ticketRepository.findByEventId(eventId);
            // Note: we update ticket statuses after processing refunds to avoid
            // complications where we mark tickets as refunded but then encounter an error
            // during the refund process. By only updating ticket statuses after we've
            // successfully processed refunds, we can ensure that our domain state remains
            // consistent with the actual refund status of each order receipt.
            for (Ticket ticket : tickets) {
                if (ticket.getStatus() == TicketStatus.PAID || ticket.getStatus() == TicketStatus.ISSUED) {
                    ticket.markRefunded();
                } else {
                    ticket.markVoided();
                }
                ticketRepository.save(ticket);
            }
            // Finally, we mark the event as canceled. This is a soft cancel that allows us
            // to keep the event in our system for historical and reporting purposes while
            // preventing any future purchases or interactions with it. The event's status
            // will be used in various parts of the system (e.g. purchase flow, event
            // listings) to enforce the fact that it's canceled and should not be available
            // for purchase.
            event.transitionToCanceled("Event canceled by owner");
            eventRepository.save(event);

            // Notify all ticket holders
            notifyTicketHoldersOfCancellation(eventId, event.getName(), orderReceipts);

            log.info("Event {} canceled and refund flow completed", eventId);

        } catch (EventNotFoundException e) {
            log.warn("Event {} not found for cancellation", eventId);
            throw e;
        } catch (RefundFailedException e) {
            log.error("Refund failed during cancellation of event {}: {}", eventId, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            log.error("Unexpected error during cancellation of event {}: {}", eventId, e.getMessage());
            throw e;
        } finally {
            eventRepository.unlock(eventId);
        }
    }

    private void notifyTicketHoldersOfCancellation(int eventId, String eventName, List<OrderReceipt> receipts) {
        // Collect unique user IDs to avoid duplicate notifications per receipt
        java.util.Set<Integer> memberUserIds = receipts.stream()
                .filter(OrderReceipt::isMemberReceipt)
                .map(OrderReceipt::getHolderUserId)
                .filter(id -> id != null && id > 0)
                .collect(java.util.stream.Collectors.toSet());

        for (Integer userId : memberUserIds) {
            try {
                notificationService.notifyEventCancelled(userId, eventId, eventName);
            } catch (Exception e) {
                log.warn("Failed to send cancellation notification to userId={} for eventId={}", userId, eventId, e);
            }
        }
    }

    // helper function for cancelEventAndRefund to validate refund results from the
    // payment gateway and throw domain-specific exceptions if something looks
    // wrong. This keeps the main flow cleaner and centralizes refund validation
    // logic.
    private void validateRefundResult(int receiptId, double expectedRefundAmount, RefundResultDTO refundResult) {
        if (refundResult == null) {
            throw new RefundFailedException(receiptId, "payment gateway returned null refund result");
        }

        if (refundResult.refundTransactionId() == null || refundResult.refundTransactionId().isBlank()) {
            throw new RefundFailedException(receiptId, "refund transaction id is missing");
        }

        if (Math.abs(refundResult.totalRefunded() - expectedRefundAmount) > 0.0001) {
            throw new RefundFailedException(receiptId, "refund amount mismatch");
        }

        if (refundResult.refundedAt() == null) {
            throw new RefundFailedException(receiptId, "refund timestamp is missing");
        }
    }

    // UC-21 — set / replace event-level purchase + discount policies.
    // this function replaces any existing event-level purchase policy with a new
    // one built from the provided DTO, while also inheriting
    // and combining with the company-level purchase policy. If the company does not
    // have a purchase policy, it simply uses the event-specific
    // one. The resulting combined purchase policy is then set on the event and
    // saved to the repository.

    // in this function, lock the event as well to prevent concurrent modifications
    // to the event's purchase policy while we're updating it. This ensures that we
    // have a consistent view of the event's state and that we don't accidentally
    // overwrite changes made by another user at the same time.
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

            ProductionCompany company = companyRepository.getCompanyById(config.companyId());
            if (company == null) {
                throw new CompanyNotFoundException();
            }

            User user = userRepository.getUserById(userId);
            if (user == null) {
                throw new UserNotFoundException();
            }
            user.requirePermissionInCompany(config.companyId(), Permission.EDIT_POLICIES);

            Event event = eventRepository.findById(config.eventId());
            if (event == null) {
                throw new EventNotFoundException();
            }

            if (event.getCompanyId() != config.companyId()) {
                throw new UnauthorizedActionException("act on an event that does not belong to this company");
            }

           PurchasePolicy eventSpecificPurchasePolicy = buildPurchasePolicyFromDTO(config.purchasePolicy());

            event.setPurchasePolicy(eventSpecificPurchasePolicy);

            eventRepository.save(event);

            log.info("Purchase policy for event {} was updated by user {}", event.getId(), userId);
        } finally {
            eventRepository.unlock(config.eventId());
        }
    }

    private PurchasePolicy buildPurchasePolicyFromDTO(PurchasePolicyDTO dto) {
        if (dto == null) {
            return new NoPurchasePolicy();
        }

        if (dto.type() == null || dto.type().isBlank()) {
            throw new IllegalArgumentException("Purchase policy type is required");
        }

        String type = dto.type().trim().toUpperCase();

        switch (type) {
            case "AGE":
                if (dto.minimumAge() == null) {
                    throw new IllegalArgumentException("minimumAge is required for AGE policy");
                }
                return new AgePurchasePolicy(dto.minimumAge());

            case "MIN_TICKETS":
                if (dto.minimumTickets() == null) {
                    throw new IllegalArgumentException("minimumTickets is required for MIN_TICKETS policy");
                }
                return new MinTicketsPurchasePolicy(dto.minimumTickets());

            case "MAX_TICKETS":
                if (dto.maximumTickets() == null) {
                    throw new IllegalArgumentException("maximumTickets is required for MAX_TICKETS policy");
                }
                return new MaxTicketsPurchasePolicy(dto.maximumTickets());

            case "AND":
                validateCompositeChildren(dto, "AND");
                return buildAndPolicy(dto.children());

            case "OR":
                validateCompositeChildren(dto, "OR");
                return buildOrPolicy(dto.children());

            case "NONE":
                return new NoPurchasePolicy();

            default:
                throw new IllegalArgumentException("Unknown purchase policy type: " + dto.type());
        }
    }

    private void validateCompositeChildren(PurchasePolicyDTO dto, String type) {
        if (dto.children() == null || dto.children().size() < 2) {
            throw new IllegalArgumentException(type + " policy must contain at least two children");
        }
    }

    private PurchasePolicy buildAndPolicy(List<PurchasePolicyDTO> children) {
        PurchasePolicy result = buildPurchasePolicyFromDTO(children.get(0));

        for (int i = 1; i < children.size(); i++) {
            result = new AndPurchasePolicy(
                    result,
                    buildPurchasePolicyFromDTO(children.get(i)));
        }

        return result;
    }

    private PurchasePolicy buildOrPolicy(List<PurchasePolicyDTO> children) {
        PurchasePolicy result = buildPurchasePolicyFromDTO(children.get(0));

        for (int i = 1; i < children.size(); i++) {
            result = new OrPurchasePolicy(
                    result,
                    buildPurchasePolicyFromDTO(children.get(i)));
        }

        return result;
    }

    private int validateTokenAndGetUserId(String token) {
        if (!sessionManager.validateToken(token)) {
            log.warn("Invalid token provided");
            throw new InvalidTokenException();
        }
        return sessionManager.extractUserId(token);
    }

    @Transactional(readOnly = true)
    public PurchasePolicyDTO getEventPurchasePolicy(String token, int companyId, int eventId) {
        if (!sessionManager.validateToken(token))
            throw new InvalidTokenException();
        int userId = sessionManager.extractUserId(token);
        ProductionCompany company = companyRepository.getCompanyById(companyId);
        if (company == null)
            throw new CompanyNotFoundException();
        User user = userRepository.getUserById(userId);
        if (user == null)
            throw new UserNotFoundException();
        user.requirePermissionInCompany(companyId, Permission.EDIT_POLICIES);
        Event event = eventRepository.findById(eventId);
        if (event == null)
            throw new EventNotFoundException();
        if (event.getCompanyId() != companyId)
            throw new UnauthorizedActionException("act on an event that does not belong to this company");
        return policyToDTO(event.getPurchasePolicy());
    }

    private PurchasePolicyDTO policyToDTO(PurchasePolicy policy) {
        if (policy == null || policy instanceof NoPurchasePolicy)
            return new PurchasePolicyDTO("NONE", null, null, null, null);
        if (policy instanceof AgePurchasePolicy a)
            return new PurchasePolicyDTO("AGE", a.getMinimumAge(), null, null, null);
        if (policy instanceof MinTicketsPurchasePolicy m)
            return new PurchasePolicyDTO("MIN_TICKETS", null, m.getMinimumTickets(), null, null);
        if (policy instanceof MaxTicketsPurchasePolicy m)
            return new PurchasePolicyDTO("MAX_TICKETS", null, null, m.getMaximumTickets(), null);
        if (policy instanceof AndPurchasePolicy a)
            return new PurchasePolicyDTO("AND", null, null, null,
                    List.of(policyToDTO(a.getLeftPolicy()), policyToDTO(a.getRightPolicy())));
        if (policy instanceof OrPurchasePolicy o)
            return new PurchasePolicyDTO("OR", null, null, null,
                    List.of(policyToDTO(o.getLeftPolicy()), policyToDTO(o.getRightPolicy())));
        return new PurchasePolicyDTO("NONE", null, null, null, null);
    }
}
