package com.ticketing.system.ui.presenters.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.ticketing.system.Core.Application.dto.InventorySelectionDTO;
import com.ticketing.system.Core.Application.dto.InventoryZoneDTO;
import com.ticketing.system.Core.Application.dto.SeatDTO;
import com.ticketing.system.Core.Application.dto.VenueMapDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.catalog.domain.ZoneType;
import com.ticketing.system.shared.exception.DomainException;
import com.ticketing.system.ui.components.venue.VkSeat;
import com.ticketing.system.ui.components.venue.VkSeatedZonePicker;
import com.ticketing.system.ui.session.SessionIdentity;

@Component
@Slf4j
public class SeatPickerPresenter {

    private final CatalogService catalogService;
    private final ReservationService reservationService;
    private final SessionIdentity identity;

    @Autowired
    public SeatPickerPresenter(CatalogService catalogService,
                               ReservationService reservationService,
                               SessionIdentity identity) {
        this.catalogService = catalogService;
        this.reservationService = reservationService;
        this.identity = identity;
    }

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
            return new LoadOutcome.Failure("Could not load this zone — please refresh and try again");
        }
    }

    public ReserveOutcome reserveSeated(InventorySelectionDTO selection, int eventId, int zoneId) {
        try {
            if (identity.isMember()) {
                reservationService.reserveForMember(identity.memberToken(), eventId, zoneId, selection);
            } else {
                reservationService.reserveForGuest(identity.guestSessionId(), eventId, zoneId, selection);
            }
            return new ReserveOutcome.Success(selection.getSeatNumbers().size());
        } catch (DomainException e) {
            // Domain rejections (sold out, not on sale, market closed, …) carry buyer-facing messages.
            log.warn("seated reserve rejected (eventId={}, zoneId={}, seats={}): {}",
                    eventId, zoneId, selection.getSeatNumbers(), e.getMessage());
            return new ReserveOutcome.Failure(e.getMessage());
        } catch (RuntimeException e) {
            // Infrastructure errors (e.g. optimistic-lock contention) have technical messages — log the
            // full trace but hand the view a null so it shows its friendly fallback, never the raw text.
            log.warn("seated reserve errored (eventId={}, zoneId={}, seats={})",
                    eventId, zoneId, selection.getSeatNumbers(), e);
            return new ReserveOutcome.Failure(null);
        }
    }

    public ReserveOutcome reserveStanding(int quantity, int eventId, int zoneId) {
        try {
            InventorySelectionDTO selection = InventorySelectionDTO.standing(quantity);
            if (identity.isMember()) {
                reservationService.reserveForMember(identity.memberToken(), eventId, zoneId, selection);
            } else {
                reservationService.reserveForGuest(identity.guestSessionId(), eventId, zoneId, selection);
            }
            return new ReserveOutcome.Success(quantity);
        } catch (DomainException e) {
            // Domain rejections (sold out, not on sale, market closed, …) carry buyer-facing messages.
            log.warn("standing reserve rejected (eventId={}, zoneId={}, qty={}): {}",
                    eventId, zoneId, quantity, e.getMessage());
            return new ReserveOutcome.Failure(e.getMessage());
        } catch (RuntimeException e) {
            // Infrastructure errors (e.g. optimistic-lock contention) have technical messages — log the
            // full trace but hand the view a null so it shows its friendly fallback, never the raw text.
            log.warn("standing reserve errored (eventId={}, zoneId={}, qty={})",
                    eventId, zoneId, quantity, e);
            return new ReserveOutcome.Failure(null);
        }
    }

    public static List<VkSeatedZonePicker.SeatModel> buildSeatModels(InventoryZoneDTO zone, int seatStepPixels) {
        TreeMap<String, List<SeatDTO>> byRow = new TreeMap<>();
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

    public sealed interface LoadOutcome {
        record Loaded(InventoryZoneDTO zone, ZoneType zoneType) implements LoadOutcome { }
        record NotFound(String message) implements LoadOutcome { }
        record Failure(String message)  implements LoadOutcome { }
    }

    public sealed interface ReserveOutcome {
        record Success(int quantity)   implements ReserveOutcome { }
        record Failure(String message) implements ReserveOutcome { }
    }
}