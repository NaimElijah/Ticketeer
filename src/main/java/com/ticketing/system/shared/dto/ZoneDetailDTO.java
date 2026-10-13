package com.ticketing.system.shared.dto;

public record ZoneDetailDTO(
    String name,
    boolean seated,
    int rows,
    int seatsPerRow,
    int capacity,
    double price,
    GridPlacementDTO placement   // null = unplaced
) {}