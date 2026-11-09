package com.ticketing.system.shared.dto;

import java.time.LocalDate;

/**
 * A discount rule per II.4.3.2. No service reads it today: its only carrier is
 * {@link CompanyPolicyConfigDTO}'s {@code defaultDiscountPolicies}, which
 * {@code setCompanyPolicies} ignores. Coupons (II.4.3.2) are deferred to #67; their rules are OQ-3 in
 * {@code docs/requirements/requirements.md}.
 *
 * @param name           a label for the rule, such as {@code "EarlyBird"}
 * @param type           the kind of discount: {@code EARLY_BIRD}, {@code PERCENTAGE}, {@code FIXED} or
 *                       {@code COUPON}; nothing validates it
 * @param percentageOff  the percentage taken off; {@code null} when the rule has none
 * @param fixedAmountOff the amount taken off; {@code null} when the rule has none
 * @param validFrom      the first day the rule applies; {@code null} for no start
 * @param validUntil     the last day the rule applies; {@code null} for no end
 */
public record DiscountPolicyDTO(
    String name,
    String type,
    Double percentageOff,
    Double fixedAmountOff,
    LocalDate validFrom,
    LocalDate validUntil
) {}
