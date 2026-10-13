package com.ticketing.system.shared.dto;

import java.util.List;

// Lightweight Event projection for listings — UC-3 (browse), UC-7 (search results).
// Use EventDetailDTO for the per-event detail page.
public record EventSummaryDTO(
    int eventId,
    String name,
    String status,
    Double rating,
    String companyName,
    String category,
    String location,
    List<ShowDateDTO> showDates,
    double minPrice,
    double maxPrice,
    boolean soldOut,
    List<String> artistsNames
) {}
