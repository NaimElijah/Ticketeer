package com.ticketing.system.Presentation.presenters.catalog;

import java.util.List;

import org.springframework.stereotype.Component;

import com.ticketing.system.Core.Application.dto.EventDetailDTO;
import com.ticketing.system.Core.Application.dto.InventoryZoneDTO;
import com.ticketing.system.Core.Application.dto.VenueMapDTO;
import com.ticketing.system.Core.Application.services.CatalogService;
import com.ticketing.system.shared.exception.CompanyClosedException;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;

import lombok.extern.slf4j.Slf4j;

/**
 * MVP presenter for {@code EventDetailsView} (#272). Loads the public event detail plus a
 * best-effort venue/zone summary and returns a sealed {@link Outcome} the view switches on —
 * the view never calls services directly nor uses {@code try/catch}.
 */
@Slf4j
@Component
public class EventDetailsPresenter {

    /** Fallback grid when an event has no (accessible) venue map yet. */
    private static final int DEFAULT_GRID = 3;

    private final CatalogService catalogService;

    public EventDetailsPresenter(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * Loads the event header/description/schedule/lineup plus its zone summary. The venue map is
     * best-effort: an event with no accessible inventory still renders its detail (empty zones).
     */
    public Outcome load(String credential, int eventId) {
        EventDetailDTO event;
        try {
            event = catalogService.getEventDetail(credential, eventId);
        } catch (EventNotFoundException | CompanyClosedException e) {
            return new Outcome.NotFound("This event isn't available.");
        } catch (InvalidTokenException e) {
            return new Outcome.Failure("Your session has expired - please refresh and try again.");
        } catch (RuntimeException e) {
            log.warn("Failed to load event detail for eventId {}: {}", eventId, e.getMessage());
            return new Outcome.Failure("We couldn't load this event right now.");
        }

        List<InventoryZoneDTO> zones = List.of();
        int gridRows = DEFAULT_GRID;
        int gridCols = DEFAULT_GRID;
        try {
            VenueMapDTO map = catalogService.getEventVenueMap(credential, eventId);
            zones = map.inventoryZones();
            gridRows = map.gridRows();
            gridCols = map.gridCols();
        } catch (RuntimeException e) {
            // No (accessible) venue map — show the detail with an empty zones rail.
            log.debug("No accessible venue map for eventId {}: {}", eventId, e.getMessage());
        }

        // Organizer's DERIVED rating (mean of the company's events' ratings) — best-effort so a
        // failure here never blocks the page.
        Double companyRating = null;
        try {
            companyRating = catalogService.companyRating(Integer.parseInt(event.companyId()));
        } catch (RuntimeException e) {
            log.debug("Could not resolve company rating for eventId {}: {}", eventId, e.getMessage());
        }

        return new Outcome.Success(event, zones, gridRows, gridCols, companyRating);
    }

    /** Sealed outcome the view switches on to render the page or an error banner. */
    public sealed interface Outcome {
        record Success(EventDetailDTO event, List<InventoryZoneDTO> zones,
                       int gridRows, int gridCols, Double companyRating) implements Outcome { }
        record NotFound(String message) implements Outcome { }
        record Failure(String message)  implements Outcome { }
    }
}
