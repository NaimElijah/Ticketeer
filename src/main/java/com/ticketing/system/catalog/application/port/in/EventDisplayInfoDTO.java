package com.ticketing.system.catalog.application.port.in;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Sales-safe, read-only display projection of a catalog {@link com.ticketing.system.catalog.domain.Event}.
 *
 * <p>Lets read-model mappers in other contexts (e.g. sales' {@code OrderReceiptMapper}) resolve
 * human-readable event, zone, venue, and company references for purchase-history views without ever
 * importing a {@code catalog.domain} type. Every field is a primitive/String/record already flattened
 * at the catalog boundary; a {@code null} field means the underlying value was absent.
 *
 * @param eventId       the event id
 * @param eventName     the event's display name, or {@code null} if unknown
 * @param companyId     the owning production company's id (so callers can resolve the company name)
 * @param category      the event category's string form, or {@code null}
 * @param eventStartsAt the first show date's start time, or {@code null} if the event has no schedule
 * @param venueLocation the venue location's string form, or {@code null} if no venue is configured
 * @param zones         the event's zones as (id, name) pairs, for zone-name resolution
 */
public record EventDisplayInfoDTO(
        int eventId,
        String eventName,
        int companyId,
        String category,
        LocalDateTime eventStartsAt,
        String venueLocation,
        List<ZoneNameDTO> zones) {

    /**
     * Sales-safe (id, name) pairing for a single inventory zone, used to resolve a zone's display
     * name from its id without exposing the {@code catalog.domain.InventoryZone} aggregate.
     *
     * @param zoneId the zone id (unique within the event's venue map)
     * @param name   the zone's display name
     */
    public record ZoneNameDTO(int zoneId, String name) {
    }
}
