package com.ticketing.system.shared.dto;

/**
 * One row of the top-bar search (V2-SEARCH-01 / #281). It covers events, artists and venues, and every
 * row carries the representative event to open, since there are no dedicated artist or venue pages:
 * each row navigates to that event's EventDetailsView.
 *
 * @param type     {@code EVENT}, {@code ARTIST} or {@code VENUE}; drives the grouping and the icon in
 *                 the panel
 * @param title    the event name, artist name or venue location
 * @param subtitle secondary context: the company name, or {@code "Artist · {event}"} or
 *                 {@code "Venue · {event}"}
 * @param eventId  the representative event to open
 */
public record SearchResultDTO(
    String type,
    String title,
    String subtitle,
    int eventId
) {}
