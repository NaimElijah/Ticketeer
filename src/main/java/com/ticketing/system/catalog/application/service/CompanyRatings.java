package com.ticketing.system.catalog.application.service;

import java.util.Collection;

import com.ticketing.system.catalog.domain.Event;

/**
 * A production company's rating is <b>derived, not stored</b>: it is the mean of its events'
 * ratings — events without a rating are ignored — or {@code null} when none of its events are
 * rated. The result is rounded to one decimal place.
 *
 * <p>Single source of truth for the company rating surfaced by the catalogue Organizer-rating
 * filter ({@code CatalogService}) and the owner workspace dashboard ({@code CompanyAnalyticsService}).
 * Lives in {@code catalog} because it operates purely on {@link Event}s; {@code organization} no
 * longer owns any catalog-derived rating logic (keeps organization from importing catalog).
 */
public final class CompanyRatings {

    // Utility holder — never instantiated.
    private CompanyRatings() { }

    /**
     * Mean of the non-null event ratings (1-decimal), or {@code null} if no event is rated.
     *
     * @param events the company's events (may be {@code null})
     * @return the rounded mean rating, or {@code null} when there is nothing rated to average
     */
    public static Double fromEvents(Collection<Event> events) {
        if (events == null) {
            return null;
        }
        double sum = 0;
        int count = 0;
        for (Event event : events) {
            Double rating = event.getRating();
            if (rating != null) {
                sum += rating;
                count++;
            }
        }
        // Null rather than 0.0 when nothing is rated, so a company with no rating is not read as rated zero.
        if (count == 0) {
            return null;
        }
        return Math.round((sum / count) * 10.0) / 10.0;
    }
}
