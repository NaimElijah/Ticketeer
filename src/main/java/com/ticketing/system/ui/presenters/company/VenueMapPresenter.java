package com.ticketing.system.ui.presenters.company;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.GridPlacementDTO;
import com.ticketing.system.shared.dto.VenueLayoutDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * Presenter for {@code VenueMapEditorView}: loads an event's zones, saves its venue map, and turns the
 * zone form's inputs into a validated zone. Holds no Vaadin imports; each call takes the member token
 * the view reads from its session and returns a typed outcome.
 */
@Component
public class VenueMapPresenter {

    private static final String DEFAULT_ZONE_NAME_PREFIX = "Zone ";

    private final EventManagementService eventService;

    /** Creates the presenter over the event management service. */
    @Autowired
    public VenueMapPresenter(EventManagementService eventService) {
        this.eventService = eventService;
    }

    /**
     * Reads the current zones of an event, with their live inventory.
     *
     * @param token   the caller's member token; {@code null} gives {@link LoadOutcome.NotAuthenticated}
     * @param eventId the event whose zones to read
     * @return {@link LoadOutcome.Success} with the layout; {@link LoadOutcome.Failure} with
     *     {@code ServiceErrors}' text when the service refuses, for example for a caller without the
     *     {@code CONFIGURE_VENUE} permission
     */
    public LoadOutcome loadZones(String token, int eventId) {
        if (token == null) return new LoadOutcome.NotAuthenticated();
        try {
            VenueLayoutDTO layout = eventService.getEventZones(token, eventId);
            return new LoadOutcome.Success(layout);
        } catch (InvalidTokenException e) {
            return new LoadOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new LoadOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /**
     * Saves the venue map of an event to the company that owns the event.
     *
     * @param token   the caller's member token; {@code null} gives {@link SaveOutcome.NotAuthenticated}
     * @param eventId the event's id as text, as the view holds it
     * @param config  the venue map to store
     * @return {@link SaveOutcome.Failure} with {@code ServiceErrors}' text when the service refuses the
     *     map or the caller, or fails
     */
    public SaveOutcome saveMap(String token, String eventId, VenueMapConfigDTO config) {
        if (token == null) return new SaveOutcome.NotAuthenticated();
        try {
            // The venue map must be saved to the EVENT's own company — not "the caller's first
            // company". Resolving from the event (a) targets the correct company for a multi-company
            // owner and (b) works for a manager granted CONFIGURE_VENUE, who has no Owner appointment
            // for an owner-only lookup to return. getEventDetail needs only active membership of the
            // event's company; configureVenueMap is what checks CONFIGURE_VENUE.
            int companyId = Integer.parseInt(
                    eventService.getEventDetail(token, Integer.parseInt(eventId)).companyId());
            eventService.configureVenueMap(token, companyId, config);
            return new SaveOutcome.Success();
        } catch (InvalidTokenException e) {
            return new SaveOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new SaveOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /**
     * The name a new zone gets in the editor: the lowest "Zone n" that no zone of the map uses, ignoring
     * case. A venue map refuses two zones named alike, and numbering by position repeats a name once a
     * zone before the last is removed.
     *
     * @param takenNames the names of the map's current zones
     * @return the first free default name; "Zone 1" for a map with no zones
     */
    public String defaultZoneName(List<String> takenNames) {
        Set<String> taken = takenNames.stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        for (int number = 1; ; number++) {
            String candidate = DEFAULT_ZONE_NAME_PREFIX + number;
            if (!taken.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
    }

    /**
     * Turns the zone form's inputs into a zone. A missing row, seats-per-row or standing-capacity
     * count becomes 1.
     *
     * @param name            the zone name as typed
     * @param priceText       the price as typed; surrounding whitespace is ignored
     * @param seated          {@code true} for a seated zone, {@code false} for a standing one
     * @param rows            the number of rows, or {@code null}
     * @param seatsPerRow     the seats in each row, or {@code null}
     * @param standingCapacity the capacity of a standing zone, or {@code null}; ignored for a seated zone
     * @param placement       where the zone sits on the map grid
     * @return {@link ZoneOutcome.InvalidName} when the name is {@code null} or blank;
     *     {@link ZoneOutcome.InvalidPrice} when the price is not a number; otherwise
     *     {@link ZoneOutcome.Valid}
     */
    public ZoneOutcome validateZone(String name, String priceText, boolean seated,
                                    Integer rows, Integer seatsPerRow, Integer standingCapacity,
                                    GridPlacementDTO placement) {
        if (name == null || name.isBlank()) return new ZoneOutcome.InvalidName();
        double price;
        try { price = Double.parseDouble(priceText == null ? "" : priceText.trim()); }
        catch (NumberFormatException e) { return new ZoneOutcome.InvalidPrice(); }
        int r   = rows == null ? 1 : rows;
        int spr = seatsPerRow == null ? 1 : seatsPerRow;
        int cap = seated ? 0 : (standingCapacity == null ? 1 : standingCapacity);
        return new ZoneOutcome.Valid(name, seated, r, spr, cap, price, placement);
    }

    /** Sealed outcome of {@link #validateZone}, which the view switches on. */
    public sealed interface ZoneOutcome {
        /**
         * The inputs form a zone.
         *
         * @param name         the zone name, as typed
         * @param seated       whether the zone is seated
         * @param rows         the number of rows
         * @param seatsPerRow  the seats in each row
         * @param capacity     the standing capacity; 0 for a seated zone
         * @param price        the ticket price
         * @param placement    where the zone sits on the map grid
         */
        record Valid(String name, boolean seated, int rows, int seatsPerRow,
                    int capacity, double price, GridPlacementDTO placement) implements ZoneOutcome {}

        /** The name is {@code null} or blank. */
        record InvalidName()  implements ZoneOutcome {}

        /** The price text is not a number. */
        record InvalidPrice() implements ZoneOutcome {}
    }

    /** Sealed outcome of {@link #loadZones}, which the view switches on. */
    public sealed interface LoadOutcome {
        /**
         * The zones loaded.
         *
         * @param layout the event's zones with their live inventory
         */
        record Success(VenueLayoutDTO layout) implements LoadOutcome {}

        /** There is no token, or the service rejected it as invalid. */
        record NotAuthenticated() implements LoadOutcome {}

        /**
         * The service refused the read or failed.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements LoadOutcome {}
    }

    /** Sealed outcome of {@link #saveMap}, which the view switches on. */
    public sealed interface SaveOutcome {
        /** The service stored the venue map. */
        record Success() implements SaveOutcome {}

        /** There is no token, or the service rejected it as invalid. */
        record NotAuthenticated() implements SaveOutcome {}

        /**
         * The service refused the map or the caller, or failed.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements SaveOutcome {}
    }
}