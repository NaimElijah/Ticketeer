package com.ticketing.system.catalog.application.dtoMappers;

import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.shared.dto.EventSummaryDTO;
import com.ticketing.system.shared.dto.ShowDateDTO;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.Location;

public class EventMapper {


    /** Cheapest ticket price across the event's zones, or 0 when it has no venue map / no zones. */
    public static double minZonePrice(Event event) {
        return (event.getVenueMap() != null && !event.getVenueMap().getInventoryZones().isEmpty())
                ? event.getVenueMap().getInventoryZones().stream().mapToDouble(z -> z.getprice()).min().getAsDouble()
                : 0;
    }

    // HELPER METHOD to convert Event --> EventSummaryDTO; used in both search methods above.
    public EventSummaryDTO convertEventToEventSummaryDTO(Event event, ProductionCompanyRepository productionCompanyRepository) {
        double minPrice = minZonePrice(event);
        double maxPrice = (event.getVenueMap() != null && !event.getVenueMap().getInventoryZones().isEmpty())
                ? event.getVenueMap().getInventoryZones().stream().mapToDouble(z -> z.getprice()).max().getAsDouble()
                : 0;
        return new EventSummaryDTO(
                event.getId(),
                event.getName(),
                event.getStatus().toString(),
                event.getRating(),
                productionCompanyRepository.getCompanyById(event.getCompanyId()).getName(),
                event.getCategory().toString(),
                event.getVenueMap().getLocation().toString(),
                event.getShowDates().stream().map(sd -> new ShowDateDTO(sd.getStartTime(), sd.getEndTime())).toList(),
                minPrice,
                maxPrice,
                event.getStatus() == EventStatus.SOLD_OUT,
                event.getArtistsNames());
    }


    // HELPER METHOD to convert Event --> EventDetailDTO (full detail, incl. lineup).
    // Used by EventManagementService (owner-side) and CatalogService (guest-side); the
    // caller resolves the company name since the two services reach the company differently.
    public EventDetailDTO toEventDetailDTO(Event event, String companyName) {
        Location location = event.getVenueMap() != null ? event.getVenueMap().getLocation() : null;
        return new EventDetailDTO(
                String.valueOf(event.getId()),
                event.getName(),
                event.getRating(),
                event.getDescription(),
                event.getCategory(),
                location,
                String.valueOf(event.getCompanyId()),
                companyName,
                event.getStatus(),
                event.getShowDates(),
                event.getArtistsNames(),
                minZonePrice(event));
    }


}
