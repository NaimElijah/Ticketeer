package com.ticketing.system.ui.presenters.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.shared.dto.InventoryZoneDTO;
import com.ticketing.system.shared.dto.SeatDTO;
import com.ticketing.system.shared.dto.VenueMapDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.catalog.domain.ZoneType;
import com.ticketing.system.shared.exception.DomainException;
import com.ticketing.system.ui.components.venue.VkSeat;
import com.ticketing.system.ui.components.venue.VkSeatedZonePicker;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * Presenter for the seat and standing-zone picker of an event. It loads one zone of an event's venue
 * map, reserves seats or standing places for the visitor, and lays seats out as a grid; every service
 * failure comes back as a typed outcome.
 *
 * <p>The visitor is resolved through {@link SessionIdentity}: a live member reserves with the member
 * token, anyone else with the guest session id.
 */
@Component
@Slf4j
public class SeatPickerPresenter {

    // Row labels are bijective base-26 (SeatLabels.rowLabel: A..Z, then AA, AB, ...), so a shorter label
    // is always an earlier row; plain string order would draw AA between A and B.
    private static final Comparator<String> ROW_ORDER =
            Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder());

    private final CatalogService catalogService;
    private final ReservationService reservationService;
    private final SessionIdentity identity;

    /**
     * Creates the presenter.
     *
     * @param catalogService     reads the event's venue map
     * @param reservationService reserves the seats or places
     * @param identity           resolves the visitor as a member or a guest
     */
    @Autowired
    public SeatPickerPresenter(CatalogService catalogService,
                               ReservationService reservationService,
                               SessionIdentity identity) {
        this.catalogService = catalogService;
        this.reservationService = reservationService;
        this.identity = identity;
    }

    /**
     * Loads one zone of an event's venue map for the visitor.
     *
     * @param eventId the event whose venue map to read
     * @param zoneId  the zone to pick out of the map
     * @return {@link LoadOutcome.Loaded} with the zone and its type; {@link LoadOutcome.NotFound} when
     *     the map has no such zone; {@link LoadOutcome.Failure} when the map cannot be read
     */
    public LoadOutcome loadZone(int eventId, int zoneId) {
        try {
            VenueMapDTO map = catalogService.getEventVenueMap(identity.credential(), eventId);
            if (map == null) {
                return new LoadOutcome.Failure("Could not load venue map");
            }
            InventoryZoneDTO zone = map.inventoryZones().stream()
                    .filter(z -> z.getId() == zoneId)
                    .findFirst()
                    .orElse(null);
            if (zone == null) {
                return new LoadOutcome.NotFound("That zone is no longer available");
            }
            ZoneType zoneType = ZoneType.valueOf(zone.getZoneType());
            return new LoadOutcome.Loaded(zone, zoneType);
        } catch (RuntimeException e) {
            return new LoadOutcome.Failure(ServiceErrors.userMessageLogged(e,
                    "load zone eventId=" + eventId + " zoneId=" + zoneId));
        }
    }

    /**
     * Reserves the selected seats of a seated zone for the visitor.
     *
     * @param selection the seats to reserve, by seat number
     * @param eventId   the event the zone belongs to
     * @param zoneId    the seated zone
     * @return {@link ReserveOutcome.Success} with the number of seats reserved, or
     *     {@link ReserveOutcome.Failure} with the refusal's message when the service throws a
     *     {@code DomainException}, and with {@code ServiceErrors}' text for any other failure
     */
    public ReserveOutcome reserveSeated(InventorySelectionDTO selection, int eventId, int zoneId) {
        try {
            reserveForVisitor(eventId, zoneId, selection);
            return new ReserveOutcome.Success(selection.getSeatNumbers().size());
        } catch (DomainException e) {
            // Domain rejections (sold out, not on sale, market closed, …) carry buyer-facing messages. A
            // refusal is not a degraded state, and LoggingAspect already logs it at WARN, so DEBUG here.
            log.debug("seated reserve rejected (eventId={}, zoneId={}, seats={}): {}",
                    eventId, zoneId, selection.getSeatNumbers(), e.getMessage());
            return new ReserveOutcome.Failure(e.getMessage());
        } catch (RuntimeException e) {
            return new ReserveOutcome.Failure(ServiceErrors.userMessageLogged(e, "seated reserve eventId=" + eventId
                    + " zoneId=" + zoneId + " seats=" + selection.getSeatNumbers()));
        }
    }

    /**
     * Reserves places in a standing zone for the visitor.
     *
     * @param quantity the number of places
     * @param eventId  the event the zone belongs to
     * @param zoneId   the standing zone
     * @return {@link ReserveOutcome.Success} with {@code quantity}, or {@link ReserveOutcome.Failure}
     *     with the refusal's message when the service throws a {@code DomainException}, and with
     *     {@code ServiceErrors}' text for any other failure
     */
    public ReserveOutcome reserveStanding(int quantity, int eventId, int zoneId) {
        try {
            reserveForVisitor(eventId, zoneId, InventorySelectionDTO.standing(quantity));
            return new ReserveOutcome.Success(quantity);
        } catch (DomainException e) {
            // Domain rejections carry buyer-facing messages; DEBUG for the same reason as reserveSeated.
            log.debug("standing reserve rejected (eventId={}, zoneId={}, qty={}): {}",
                    eventId, zoneId, quantity, e.getMessage());
            return new ReserveOutcome.Failure(e.getMessage());
        } catch (RuntimeException e) {
            return new ReserveOutcome.Failure(ServiceErrors.userMessageLogged(e, "standing reserve eventId=" + eventId
                    + " zoneId=" + zoneId + " qty=" + quantity));
        }
    }

    // One resolution per reservation: asking isMember() and then memberToken() would check the token twice.
    private void reserveForVisitor(int eventId, int zoneId, InventorySelectionDTO selection) {
        switch (identity.visitor()) {
            case SessionIdentity.Visitor.Member member ->
                    reservationService.reserveForMember(member.token(), eventId, zoneId, selection);
            case SessionIdentity.Visitor.Guest guest ->
                    reservationService.reserveForGuest(guest.sessionId(), eventId, zoneId, selection);
        }
    }

    /**
     * Lays a seated zone's seats out as a grid: one row per row label, top to bottom, and the seats of
     * a row left to right by seat number. The seats' own coordinates are ignored.
     *
     * @param zone           the zone whose seats to lay out
     * @param seatStepPixels the distance between two neighbouring seats, across and down
     * @return one model per seat, in row order
     */
    public static List<VkSeatedZonePicker.SeatModel> buildSeatModels(InventoryZoneDTO zone, int seatStepPixels) {
        TreeMap<String, List<SeatDTO>> byRow = new TreeMap<>(ROW_ORDER);
        for (SeatDTO s : zone.getSeats()) {
            byRow.computeIfAbsent(rowOf(s.label()), k -> new ArrayList<>()).add(s);
        }
        List<VkSeatedZonePicker.SeatModel> models = new ArrayList<>();
        int rowIdx = 0;
        for (List<SeatDTO> rowSeats : byRow.values()) {
            rowSeats.sort(Comparator.comparingInt(s -> seatNumber(s.label())));
            int colIdx = 0;
            for (SeatDTO s : rowSeats) {
                models.add(new VkSeatedZonePicker.SeatModel(
                        s.label(), colIdx * seatStepPixels, rowIdx * seatStepPixels, stateFor(s.status())));
                colIdx++;
            }
            rowIdx++;
        }
        return models;
    }

    private static VkSeat.State stateFor(String status) {
        return switch (status) {
            case "AVAILABLE" -> VkSeat.State.free;
            case "SOLD"      -> VkSeat.State.sold;
            default          -> VkSeat.State.held;
        };
    }

    private static String rowOf(String label) {
        int i = 0;
        while (i < label.length() && !Character.isDigit(label.charAt(i))) {
            i++;
        }
        return i > 0 ? label.substring(0, i) : label;
    }

    private static int seatNumber(String label) {
        int i = 0;
        while (i < label.length() && !Character.isDigit(label.charAt(i))) {
            i++;
        }
        try {
            return Integer.parseInt(label.substring(i));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Sealed outcome of {@link #loadZone}, which the view switches on. */
    public sealed interface LoadOutcome {
        /**
         * The zone was found.
         *
         * @param zone     the zone, with its seats' current status
         * @param zoneType whether the zone is seated or standing
         */
        record Loaded(InventoryZoneDTO zone, ZoneType zoneType) implements LoadOutcome { }

        /**
         * The venue map has no zone with that id.
         *
         * @param message fixed text for the user
         */
        record NotFound(String message) implements LoadOutcome { }

        /**
         * The venue map could not be read.
         *
         * @param message the text the user may read
         */
        record Failure(String message)  implements LoadOutcome { }
    }

    /** Sealed outcome of a reservation, which the view switches on. */
    public sealed interface ReserveOutcome {
        /**
         * The service reserved the seats or places.
         *
         * @param quantity how many were reserved
         */
        record Success(int quantity)   implements ReserveOutcome { }

        /**
         * The service refused the reservation or failed.
         *
         * @param message the text the user may read: the refusal's own message for a
         *     {@code DomainException}, else from {@code ServiceErrors}
         */
        record Failure(String message)  implements ReserveOutcome { }
    }
}