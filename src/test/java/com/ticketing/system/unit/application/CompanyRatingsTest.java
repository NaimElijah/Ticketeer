package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ticketing.system.organization.application.service.CompanyRatings;
import com.ticketing.system.catalog.domain.Event;

/** A company's rating is the mean of its events' (non-null) ratings, 1-decimal, or null if none. */
class CompanyRatingsTest {

    private static Event rated(Double rating) {
        Event e = mock(Event.class);
        when(e.getRating()).thenReturn(rating);
        return e;
    }

    @Test
    void averagesNonNullRatings_roundedToOneDecimal() {
        // (4.8 + 4.9) / 2 = 4.85 -> 4.9
        assertEquals(4.9, CompanyRatings.fromEvents(List.of(rated(4.8), rated(4.9))), 0.0001);
    }

    @Test
    void ignoresUnratedEvents() {
        assertEquals(4.6, CompanyRatings.fromEvents(Arrays.asList(rated(4.6), rated(null))), 0.0001);
    }

    @Test
    void nullWhenNoRatedEventsOrEmptyOrNull() {
        assertNull(CompanyRatings.fromEvents(List.of(rated(null))));
        assertNull(CompanyRatings.fromEvents(List.of()));
        assertNull(CompanyRatings.fromEvents(null));
    }
}
