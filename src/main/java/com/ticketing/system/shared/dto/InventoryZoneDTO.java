package com.ticketing.system.shared.dto;

import java.util.List;

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

    public GridPlacementDTO getPlacement() {
        return placement;
    }

}
