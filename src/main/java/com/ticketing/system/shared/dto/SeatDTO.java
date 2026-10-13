package com.ticketing.system.shared.dto;

public record SeatDTO(
        String label,
        double x,
        double y,
        String status
) {}