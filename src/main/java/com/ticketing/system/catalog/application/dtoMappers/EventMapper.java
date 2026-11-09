package com.ticketing.system.catalog.application.dtoMappers;

import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.shared.dto.EventSummaryDTO;
import com.ticketing.system.shared.dto.ShowDateDTO;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.Location;

/**
 * Maps an {@link Event} to the DTOs the catalog returns: the summary row of a search or browse list, and
 * the full detail of one event.
 */
public class EventMapper {

    /**
     * Returns the cheapest ticket price across the event's zones.
     *
     * @param event the event
     * @return the lowest zone price, or 0 when the event has no venue map or no zones
     */
    public static double minZonePrice(Event event) {
        return (event.getVenueMap() != null && !event.getVenueMap().getInventoryZones().isEmpty())
                ? event.getVenueMap().getInventoryZones().stream().mapToDouble(z -> z.getprice()).min().getAsDouble()
                : 0;
    }

    /**
     * Converts an event to the summary row of CatalogService's browse, featured, upcoming and search
     * results. The event must have a venue map with a location. The price range runs from the cheapest
     * zone to the dearest, both 0 for an event with no zones.
     *
     * @param event                       the event
     * @param productionCompanyRepository where the owning company's name is looked up
     * @return the summary; its sold-out flag is set when the event is SOLD_OUT
     * @throws com.ticketing.system.shared.exception.CompanyNotFoundException if the owning company does
     *     not exist
     */
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
                event.getTimeZone(),
                minPrice,
                maxPrice,
                event.getStatus() == EventStatus.SOLD_OUT,
                event.getArtistsNames());
    }


    /**
     * Converts an event to its full detail, including the lineup. Both EventManagementService (owner
     * side) and CatalogService (buyer side) use it; the caller resolves the company name, since the two
     * services reach the company differently.
     *
     * @param event       the event
     * @param companyName the name of the company the caller has already loaded
     * @return the detail; its location is {@code null} when the event has no venue map
     */
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
                event.getTimeZone(),
                event.getArtistsNames(),
                minZonePrice(event));
    }


}
