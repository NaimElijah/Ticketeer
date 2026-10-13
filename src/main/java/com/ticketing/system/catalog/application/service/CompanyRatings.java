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
        if (events == null) {                       // no events supplied → no derivable rating
            return null;
        }
        double sum = 0;                             // running total of the rated events' ratings
        int count = 0;                              // how many events actually carried a rating
        for (Event event : events) {                // walk every event of the company
            Double rating = event.getRating();      // an event's rating may be null (unrated)
            if (rating != null) {                   // only rated events contribute to the mean
                sum += rating;                      // accumulate the rating
                count++;                            // and count it towards the divisor
            }
        }
        if (count == 0) {                           // nothing was rated → null rather than 0.0
            return null;
        }
        return Math.round((sum / count) * 10.0) / 10.0; // mean, rounded to one decimal place
    }
}
