package com.ticketing.system.catalog.domain;

/**
 * The kind of an {@link Event}, stored by name. The catalogue search matches a category filter against
 * these names ignoring case.
 */
public enum EventCategory {
    MUSIC,
    THEATER,
    SPORTS,
    COMEDY,
    FESTIVAL,
    CONCERT,
    OTHER
}
