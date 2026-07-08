package com.ticketing.system.Core.Application.dto;
import com.ticketing.system.organization.application.service.CompanyManagementService;

import java.util.List;

// Input to CompanyManagementService.setCompanyPolicies() (UC-21 company-level).
// Same shape as event-level, just scoped to the company instead of one event.
public record CompanyPolicyConfigDTO(
    int companyId,
    PurchasePolicyDTO defaultPurchasePolicy,
    List<DiscountPolicyDTO> defaultDiscountPolicies
) {}
