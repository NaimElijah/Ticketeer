package com.ticketing.system.Presentation.presenters.company;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.Core.Application.dto.GridPlacementDTO;
import com.ticketing.system.Core.Application.dto.VenueLayoutDTO;
import com.ticketing.system.Core.Application.dto.VenueMapConfigDTO;
import com.ticketing.system.Core.Application.services.EventManagementService;
import com.ticketing.system.shared.exception.InvalidTokenException;

@Component
public class VenueMapPresenter {

    private final EventManagementService eventService;

    @Autowired
    public VenueMapPresenter(EventManagementService eventService) {
        this.eventService = eventService;
    }

    public LoadOutcome loadZones(String token, int eventId) {
        if (token == null) return new LoadOutcome.NotAuthenticated();
        try {
            VenueLayoutDTO layout = eventService.getEventZones(token, eventId);
            return new LoadOutcome.Success(layout);
        } catch (InvalidTokenException e) {
            return new LoadOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new LoadOutcome.Failure(e.getMessage());
        }
    }

    public SaveOutcome saveMap(String token, String eventId, VenueMapConfigDTO config) {
        if (token == null) return new SaveOutcome.NotAuthenticated();
        try {
            // The venue map must be saved to the EVENT's own company — not "the caller's first
            // company". Resolving from the event (a) targets the correct company for a multi-company
            // owner and (b) works for a manager granted CONFIGURE_VENUE, who has no Owner appointment
            // for an owner-only lookup to return. getEventDetail itself requires CONFIGURE_VENUE.
            int companyId = Integer.parseInt(
                    eventService.getEventDetail(token, Integer.parseInt(eventId)).companyId());
            eventService.configureVenueMap(token, companyId, config);
            return new SaveOutcome.Success();
        } catch (InvalidTokenException e) {
            return new SaveOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new SaveOutcome.Failure(e.getMessage());
        }
    }

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

    public sealed interface ZoneOutcome {
        record Valid(String name, boolean seated, int rows, int seatsPerRow,
                    int capacity, double price, GridPlacementDTO placement) implements ZoneOutcome {}
        record InvalidName()  implements ZoneOutcome {}
        record InvalidPrice() implements ZoneOutcome {}
    }

    public sealed interface LoadOutcome {
        record Success(VenueLayoutDTO layout) implements LoadOutcome {}
        record NotAuthenticated() implements LoadOutcome {}
        record Failure(String reason) implements LoadOutcome {}
    }

    public sealed interface SaveOutcome {
        record Success() implements SaveOutcome {}
        record NotAuthenticated() implements SaveOutcome {}
        record NoCompany() implements SaveOutcome {}
        record Failure(String reason) implements SaveOutcome {}
    }
}