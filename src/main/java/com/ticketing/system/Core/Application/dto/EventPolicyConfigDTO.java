package com.ticketing.system.Core.Application.dto;
import com.ticketing.system.catalog.application.service.EventManagementService;

// Input to EventManagementService.setEventPolicies() (UC-21 event-level).
public record EventPolicyConfigDTO(
    int companyId,
        int eventId,
      PurchasePolicyDTO purchasePolicy
) {}
