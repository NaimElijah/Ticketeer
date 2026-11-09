package com.ticketing.system.shared.dto;

import java.time.LocalDate;

/**
 * The optional filters of a catalog search (UC-7), input to {@code CatalogService.searchGlobal} and
 * {@code searchByCompany}.
 *
 * <p>A {@code null} component is an absent filter; an event must satisfy every filter that is set.
 * The search methods refuse a range whose lower bound is above its upper bound, and a
 * {@code fromDate} after {@code toDate}, with {@code IllegalArgumentException}, and text longer than
 * the user-text limit with {@code BusinessRuleViolationException}. The company-rating bounds apply to
 * a global search only; {@code searchByCompany} drops them.
 *
 * @param eventName        text the event's name contains, case aside
 * @param artistName       text that at least one of the event's artist names contains, case aside
 * @param category         an event category name, case aside; an unknown name matches no event
 * @param keywords         text that the event's name or any artist's name contains, case aside
 * @param minPrice         inclusive lower bound on the event's cheapest ticket price, which is its
 *                         lowest zone price; an event with no zone matches no price filter
 * @param maxPrice         inclusive upper bound on that price
 * @param fromDate         inclusive lower bound on the date, at the venue, that a show starts; at least
 *                         one show date must fall inside {@code fromDate} to {@code toDate}
 * @param toDate           inclusive upper bound on that date
 * @param location         text that the venue's city or country contains, case aside
 * @param minEventRating   inclusive lower bound on the event's rating; an unrated event fails any bound
 * @param maxEventRating   inclusive upper bound on the event's rating
 * @param minCompanyRating inclusive lower bound on the company's rating, which is derived from its
 *                         events' ratings; a company with no rated event fails any bound
 * @param maxCompanyRating inclusive upper bound on the company's rating
 */
public record CatalogSearchFiltersDTO(
        String eventName,
        String artistName,
        String category,
        String keywords,
        Double minPrice,
        Double maxPrice,
        LocalDate fromDate,
        LocalDate toDate,
        String location,
        Double minEventRating,
        Double maxEventRating,
        Double minCompanyRating,
        Double maxCompanyRating
) {
    /** Returns filters with no bound set, which match every event. */
    public static CatalogSearchFiltersDTO empty() {
        return new CatalogSearchFiltersDTO(
                null, null, null, null,
                null, null,
                null, null,
                null,
                null, null,
                null, null
        );
    }

    /** Returns a copy in which blank text is {@code null} and other text is trimmed. */
    public CatalogSearchFiltersDTO normalized() {
        return new CatalogSearchFiltersDTO(
                blankToNull(eventName),
                blankToNull(artistName),
                blankToNull(category),
                blankToNull(keywords),
                minPrice,
                maxPrice,
                fromDate,
                toDate,
                blankToNull(location),
                minEventRating,
                maxEventRating,
                minCompanyRating,
                maxCompanyRating
        );
    }

    /** Returns the {@linkplain #normalized() normalized} filters with both company-rating bounds cleared. */
    public CatalogSearchFiltersDTO withoutCompanyRating() {
        CatalogSearchFiltersDTO f = normalized();
        return new CatalogSearchFiltersDTO(
                f.eventName(),
                f.artistName(),
                f.category(),
                f.keywords(),
                f.minPrice(),
                f.maxPrice(),
                f.fromDate(),
                f.toDate(),
                f.location(),
                f.minEventRating(),
                f.maxEventRating(),
                null,
                null
        );
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
