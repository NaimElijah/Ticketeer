package com.ticketing.system.catalog.application.port.in;

/**
 * Sales-safe, flat description of one purchased/held inventory line for the inbound inventory port.
 *
 * <p>Deliberately carries only primitives — an event id, a zone id, and an optional seat label — so
 * the sales context can drive catalog's inventory confirm/release/return operations without importing
 * any {@code catalog.domain} type (no {@code InventorySelection}, {@code Seat}, or {@code Event}).
 * A {@code null} {@code seatNumber} denotes a single STANDING unit in the zone; a non-null value
 * denotes a specific seated seat. The port groups these lines by event and zone at the boundary and
 * translates them into the domain {@code InventorySelection}.
 *
 * @param eventId    the catalog event the line belongs to
 * @param zoneId     the zone within that event's venue map
 * @param seatNumber the seat label for a seated line, or {@code null} for one standing unit
 */
public record InventoryLineDTO(int eventId, int zoneId, String seatNumber) {
}
