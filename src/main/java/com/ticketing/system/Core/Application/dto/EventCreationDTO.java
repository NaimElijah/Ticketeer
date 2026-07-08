package com.ticketing.system.Core.Application.dto;

import java.util.List;

import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;

public record EventCreationDTO(
        int companyId,
        String name,
        String description,
        List<String> artistsNames,
        EventCategory category,
        Double rating,                
        Location location,             // might need to be LocationDTO
        List<ShowDate> showDates,      // might need to be List<ShowDateDTO>
        PurchasePolicyDTO purchasePolicy
) {}