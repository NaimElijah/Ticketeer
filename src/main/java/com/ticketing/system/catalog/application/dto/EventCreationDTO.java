package com.ticketing.system.catalog.application.dto;

import java.time.ZoneId;
import java.util.List;

import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.shared.dto.PurchasePolicyDTO;

/**
 * The input of {@code EventManagementService.addEvent} (UC-19): the details of a new event.
 *
 * @param companyId     the id of the company that will own the event
 * @param name          the event's name
 * @param description   the event's description, or {@code null} for none
 * @param artistsNames  the lineup; at least one name
 * @param category      the event's category
 * @param rating        the event's rating from 0 to 5, or {@code null} if unrated
 * @param location      the venue's location; {@code addEvent} refuses a request without one
 * @param showDates     the shows, as wall-clock times at the venue read in {@code timeZone}; at least
 *                      one, each starting in the future
 * @param timeZone      the venue's zone the show dates are read in (ADR-008)
 * @param purchasePolicy the event's own purchase policy, or {@code null} for none
 */
public record EventCreationDTO(
        int companyId,
        String name,
        String description,
        List<String> artistsNames,
        EventCategory category,
        Double rating,
        Location location,
        List<ShowDate> showDates,
        ZoneId timeZone,
        PurchasePolicyDTO purchasePolicy
) {}