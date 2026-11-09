package com.ticketing.system.shared.dto;

import java.util.List;

/**
 * A purchase policy as the policy editors read and save it; {@link PurchasePolicyDtoMapper} converts
 * it to and from the domain policy.
 *
 * @param type           {@code NONE}, {@code AGE}, {@code MIN_TICKETS}, {@code MAX_TICKETS},
 *                       {@code AND} or {@code OR} (see {@link PurchasePolicyDtoMapper.PolicyType});
 *                       read case-insensitively
 * @param minimumAge     the minimum age; required for {@code AGE}
 * @param minimumTickets the minimum tickets per purchase; required for {@code MIN_TICKETS}
 * @param maximumTickets the maximum tickets per purchase; required for {@code MAX_TICKETS}
 * @param children       the policies an {@code AND} or {@code OR} combines, at least two; {@code null}
 *                       for any other type
 */
public record PurchasePolicyDTO(
        String type,
        Integer minimumAge,
        Integer minimumTickets,
        Integer maximumTickets,
        List<PurchasePolicyDTO> children
) {}