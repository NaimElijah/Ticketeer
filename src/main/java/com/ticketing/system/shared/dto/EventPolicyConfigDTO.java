package com.ticketing.system.shared.dto;

// Input to EventManagementService.setEventPolicies() (UC-21 event-level).
public record EventPolicyConfigDTO(
    int companyId,
        int eventId,
      PurchasePolicyDTO purchasePolicy
) {}
