package com.ticketing.system.organization.application.service;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.organization.application.service.CompanyAnalyticsService;

import java.util.Collection;

import com.ticketing.system.catalog.domain.Event;

/**
 * A production company's rating is <b>derived, not stored</b>: it is the mean of its events'
 * ratings — events without a rating are ignored — or {@code null} when none of its events are
 * rated. The result is rounded to one decimal place.
 *
 * <p>Single source of truth for the company rating surfaced by the catalogue Organizer-rating
 * filter ({@code CatalogService}) and the owner workspace dashboard ({@code CompanyAnalyticsService}).
 */
public final class CompanyRatings {

    private CompanyRatings() { }

    /** Mean of the non-null event ratings (1-decimal), or {@code null} if no event is rated. */
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
        if (count == 0) {
            return null;
        }
        return Math.round((sum / count) * 10.0) / 10.0;
    }
}
