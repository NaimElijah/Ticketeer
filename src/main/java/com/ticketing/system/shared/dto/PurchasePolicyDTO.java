package com.ticketing.system.shared.dto;

import java.util.List;

public record PurchasePolicyDTO(
        String type,
        Integer minimumAge,
        Integer minimumTickets,
        Integer maximumTickets,
        List<PurchasePolicyDTO> children
) {}