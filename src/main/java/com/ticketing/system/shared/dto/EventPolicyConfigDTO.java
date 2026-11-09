package com.ticketing.system.shared.dto;

/**
 * Input to {@code EventManagementService.setEventPolicies} (UC-21, event level): replaces the
 * event's own purchase policy. The company's policy is not touched.
 *
 * @param companyId      the company that owns the event; the service refuses an event of another company
 * @param eventId        the event whose policy is set
 * @param purchasePolicy the event's purchase policy; {@code null} means no policy
 */
public record EventPolicyConfigDTO(
    int companyId,
        int eventId,
      PurchasePolicyDTO purchasePolicy
) {}
