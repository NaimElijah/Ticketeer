package com.ticketing.system.shared.dto;

import java.time.LocalDate;

// Configures a discount rule per II.4.3.2.
// Multiple may apply to one event/company; the service combines them.
public record DiscountPolicyDTO(
    String name,                        // e.g. "EarlyBird"
    String type,                        // "EARLY_BIRD" / "PERCENTAGE" / "FIXED" / "COUPON"
    Double percentageOff,               // nullable
    Double fixedAmountOff,              // nullable
    LocalDate validFrom,                // nullable
    LocalDate validUntil                // nullable
    // Coupons (II.4.3.2) deferred — open question #12 in design walkthrough
) {}
