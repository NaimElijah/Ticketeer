package com.ticketing.system.shared.dto;

import java.util.List;

/**
 * Input to {@code CompanyManagementService.setCompanyPolicies} (UC-21, company level).
 *
 * <p>The service stores only {@code defaultPurchasePolicy}. It does not read
 * {@code defaultDiscountPolicies}: a company has no discount to set (requirements II.4.3).
 *
 * @param defaultPurchasePolicy the company's purchase policy; {@code null} means no policy
 */
public record CompanyPolicyConfigDTO(
    int companyId,
    PurchasePolicyDTO defaultPurchasePolicy,
    List<DiscountPolicyDTO> defaultDiscountPolicies
) {}
