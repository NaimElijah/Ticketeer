package com.ticketing.system.shared.dto;

import java.util.List;

/**
 * A zone of an event's venue map with its current stock, as {@code CatalogService.getEventVenueMap}
 * returns it inside {@link VenueMapDTO}.
 */
public class InventoryZoneDTO {
    private final int id;
    private final String name;
    private final String zoneType;
    private final int capacity;
    private final int availableAmount;
    private final int reservedAmount;
    private final int soldAmount;
    private final double price;
    private final List<SeatDTO> seats;
    private final GridPlacementDTO placement;   // null = unplaced (auto-layout)

    /**
     * Creates a zone view.
     *
     * @param zoneType        {@code STANDING} or {@code SEATED}
     * @param capacity        how many tickets the zone holds
     * @param availableAmount how many tickets can still be reserved
     * @param reservedAmount  how many tickets are held in carts
     * @param soldAmount      how many tickets are sold
     * @param seats           the zone's seats; empty for a standing zone, and a {@code null} list is
     *                        stored as empty
     * @param placement       where the zone sits on the venue grid; {@code null} when it is unplaced
     */
    public InventoryZoneDTO(
            int id,
            String name,
            String zoneType,
            int capacity,
            int availableAmount,
            int reservedAmount,
            int soldAmount,
            double price,
            List<SeatDTO> seats,
            GridPlacementDTO placement
    ) {
        this.id = id;
        this.name = name;
        this.zoneType = zoneType;
        this.capacity = capacity;
        this.availableAmount = availableAmount;
        this.reservedAmount = reservedAmount;
        this.soldAmount = soldAmount;
        this.price = price;
        this.seats = seats == null ? List.of() : List.copyOf(seats);
        this.placement = placement;
    }



    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getZoneType() {
        return zoneType;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getAvailableAmount() {
        return availableAmount;
    }

    public int getReservedAmount() {
        return reservedAmount;
    }

    public int getSoldAmount() {
        return soldAmount;
    }

    public double getPrice() {
        return price;
    }

    public List<SeatDTO> getSeats() {
        return seats;
    }

    /** Returns where the zone sits on the venue grid, or {@code null} when it is unplaced. */
    public GridPlacementDTO getPlacement() {
        return placement;
    }

}
