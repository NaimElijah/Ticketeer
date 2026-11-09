package com.ticketing.system.shared.dto;

import java.time.LocalDate;

// A discount rule per II.4.3.2. No service reads it today: its only carrier is
// CompanyPolicyConfigDTO.defaultDiscountPolicies, which setCompanyPolicies ignores.
public record DiscountPolicyDTO(
    String name,                        // e.g. "EarlyBird"
    String type,                        // "EARLY_BIRD" / "PERCENTAGE" / "FIXED" / "COUPON"
    Double percentageOff,               // nullable
    Double fixedAmountOff,              // nullable
    LocalDate validFrom,                // nullable
    LocalDate validUntil                // nullable
    // Coupons (II.4.3.2) are deferred to #67; their rules are OQ-3 in docs/requirements/requirements.md
) {}
