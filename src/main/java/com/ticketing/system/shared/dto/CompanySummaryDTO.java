package com.ticketing.system.shared.dto;

/**
 * A company's id, name and rating, for a list of companies to pick from. Returned by
 * {@code CatalogService.searchCompaniesByName}, which backs the member's New Inquiry company picker
 * (II.3.10).
 *
 * @param rating the mean of the company's events' ratings; {@code null} when none is rated. How a
 *               rating is created is OQ-1 in {@code docs/requirements/requirements.md}
 */
public record CompanySummaryDTO(
    int companyId,
    String name,
    Double rating
) {}
