package com.ticketing.system.ui.presenters.catalog;

import java.util.List;

import org.springframework.stereotype.Component;

import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.shared.dto.InventoryZoneDTO;
import com.ticketing.system.shared.dto.VenueMapDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.NullVenueMapException;
import com.ticketing.system.ui.support.ServiceErrors;

import lombok.extern.slf4j.Slf4j;

/**
 * MVP presenter for {@code EventDetailsView} (upstream#272). Loads the public event detail plus its
 * venue/zone summary and returns a sealed {@link Outcome} the view switches on —
 * the view never calls services directly nor uses {@code try/catch}. Only the organizer's rating
 * is best-effort: a failure to read it leaves the page without a rating and fails nothing.
 */
@Slf4j
@Component
public class EventDetailsPresenter {

    /** Fallback grid size, in rows and in columns, when an event has no venue map yet. */
    private static final int DEFAULT_GRID = 3;

    private final CatalogService catalogService;

    /** Creates the presenter over the catalog service. */
    public EventDetailsPresenter(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * Loads the event header/description/schedule/lineup plus its zone summary. An event without a
     * venue map still renders its detail, with no zones; any other failure to read the map fails the
     * load, so a store outage never reads as an event without a map.
     *
     * @param credential the visitor's credential: a member's token or a guest's session id
     * @param eventId    the event to load
     * @return {@link Outcome.NotFound} when the event does not exist or is not publicly visible;
     *     {@link Outcome.Failure} when the credential is rejected or a read fails
     */
    public Outcome load(String credential, int eventId) {
        EventDetailDTO event;
        try {
            event = catalogService.getEventDetail(credential, eventId);
        } catch (EventNotFoundException e) {
            return new Outcome.NotFound("This event isn't available.");
        } catch (InvalidTokenException e) {
            return new Outcome.Failure("Your session has expired - please refresh and try again.");
        } catch (RuntimeException e) {
            return new Outcome.Failure(ServiceErrors.userMessageLogged(e, "load event detail eventId=" + eventId));
        }

        List<InventoryZoneDTO> zones = List.of();
        int gridRows = DEFAULT_GRID;
        int gridCols = DEFAULT_GRID;
        try {
            VenueMapDTO map = catalogService.getEventVenueMap(credential, eventId);
            zones = map.inventoryZones();
            gridRows = map.gridRows();
            gridCols = map.gridCols();
        } catch (NullVenueMapException e) {
            log.debug("No venue map for eventId {}: {}", eventId, e.getMessage());
        } catch (RuntimeException e) {
            return new Outcome.Failure(ServiceErrors.userMessageLogged(e, "load venue map eventId=" + eventId));
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
        /**
         * The event and its venue summary loaded.
         *
         * @param event         the event's header, description, schedule and lineup
         * @param zones         the zones of the event's venue map; empty when it has no map yet
         * @param gridRows      rows of the venue map's grid; a default when there is no map
         * @param gridCols      columns of the venue map's grid; a default when there is no map
         * @param companyRating the organizer's derived rating, or {@code null} when it could not be
         *     read or the company has no rated events
         */
        record Success(EventDetailDTO event, List<InventoryZoneDTO> zones,
                       int gridRows, int gridCols, Double companyRating) implements Outcome { }

        /**
         * The event does not exist or is not publicly visible.
         *
         * @param message fixed text for the user
         */
        record NotFound(String message) implements Outcome { }

        /**
         * The credential was rejected or a read failed.
         *
         * @param message the text the user may read
         */
        record Failure(String message)  implements Outcome { }
    }
}
