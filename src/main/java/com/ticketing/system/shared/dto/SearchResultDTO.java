package com.ticketing.system.shared.dto;

// One row of the top-bar search (V2-SEARCH-01 / #281). Covers events, artists, and venues; every
// row carries the representative event to open, since there are no dedicated artist/venue pages —
// each row navigates to that event's EventDetailsView.
public record SearchResultDTO(
    String type,      // "EVENT" | "ARTIST" | "VENUE" — drives grouping + icon in the panel
    String title,     // event name / artist name / venue location
    String subtitle,  // secondary context (company name, or "Artist · {event}" / "Venue · {event}")
    int eventId       // representative event to open
) {}
