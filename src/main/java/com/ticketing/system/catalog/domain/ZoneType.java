package com.ticketing.system.catalog.domain;

/** How an {@link InventoryZone} counts its inventory. */
public enum ZoneType {
    /** A counter of places with no addressable seats; see {@link StandingZone}. */
    STANDING,
    /** Named, addressable seats; see {@link SeatedZone}. */
    SEATED
}
