package com.ticketing.system.shared.dto;

/**
 * A company the signed-in member belongs to, with the member's display role. Feeds the owner-workspace
 * company selector and its name and role subtitle (V2-WIRE-OWNER-DASH). Unlike {@code ProductionCompanyDTO}
 * it carries the viewer's role rather than founder and status metadata.
 *
 * @param role {@code Founder}, {@code Co-owner} or {@code Manager}
 */
public record MyCompanyDTO(
    int companyId,
    String name,
    String role
) {}
