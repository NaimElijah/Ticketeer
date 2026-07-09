package com.ticketing.system.catalog.application.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.ticketing.system.catalog.application.port.in.CatalogEventDisplayPort;
import com.ticketing.system.catalog.application.port.in.EventDisplayInfoDTO;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.InventoryZone;

/**
 * Catalog application service implementing {@link CatalogEventDisplayPort}: it flattens an
 * {@link Event} into a sales-safe {@link EventDisplayInfoDTO} for other contexts' read models
 * (e.g. sales' {@code OrderReceiptMapper}) so they can resolve event/zone/venue/company display
 * references without importing any {@code catalog.domain} type.
 */
@Service
public class EventDisplayQueryService implements CatalogEventDisplayPort {

    // Catalog aggregate-root port used to load the event to project.
    private final EventRepository eventRepository;

    /**
     * Wires the display query service to the event store.
     *
     * @param eventRepository the Event aggregate-root port
     */
    public EventDisplayQueryService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;   // store the event port
    }

    /** {@inheritDoc} Null-safe: any load failure or missing event yields {@code null}. */
    @Override
    public EventDisplayInfoDTO describeEvent(int eventId) {
        Event event = findEvent(eventId);          // best-effort load
        if (event == null) {
            return null;                           // unknown event — let the caller render a fallback
        }
        // Flatten each display field, guarding every nested reference so a partially-configured event
        // (e.g. no venue map yet) still projects cleanly with null fields rather than throwing.
        String category = event.getCategory() == null ? null : event.getCategory().toString();
        LocalDateTime startsAt = resolveEventStart(event);
        String venueLocation = (event.getVenueMap() == null || event.getVenueMap().getLocation() == null)
                ? null
                : event.getVenueMap().getLocation().toString();
        List<EventDisplayInfoDTO.ZoneNameDTO> zones = resolveZones(event);

        return new EventDisplayInfoDTO(
                event.getId(),
                event.getName(),
                event.getCompanyId(),
                category,
                startsAt,
                venueLocation,
                zones);
    }

    /** Best-effort event load — a since-deleted event (or repo throw) becomes {@code null}. */
    private Event findEvent(int eventId) {
        try {
            return eventRepository.findById(eventId);   // may throw if absent — normalized to null below
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The first show date's start time, or {@code null} when the event has no schedule. */
    private LocalDateTime resolveEventStart(Event event) {
        if (event.getShowDates() == null || event.getShowDates().isEmpty()) {
            return null;
        }
        return event.getShowDates().get(0).getStartTime();
    }

    /** Projects each zone to a sales-safe (id, name) pair; empty when no venue map is configured. */
    private List<EventDisplayInfoDTO.ZoneNameDTO> resolveZones(Event event) {
        if (event.getVenueMap() == null) {
            return List.of();
        }
        List<EventDisplayInfoDTO.ZoneNameDTO> zones = new java.util.ArrayList<>();
        for (InventoryZone zone : event.getVenueMap().getInventoryZones()) {
            zones.add(new EventDisplayInfoDTO.ZoneNameDTO(zone.getId(), zone.getName())); // id + display name
        }
        return zones;
    }
}
