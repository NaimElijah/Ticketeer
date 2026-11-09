package com.ticketing.system.shared.dto;

import java.time.LocalDate;

/**
 * Input to {@code MessagingService.viewAllComplaints} (the admin complaint queue). A {@code null}
 * component is an absent filter.
 *
 * @param status   a {@code ConversationStatus} name, case aside; {@code null} or blank for every
 *                 status. Any other text is refused with {@code IllegalArgumentException}
 * @param memberId the member who filed the complaint
 * @param fromDate inclusive lower bound on the day the complaint was filed, in UTC
 * @param toDate   inclusive upper bound on that day
 */
public record ComplaintFilterDTO(
    String status,
    Integer memberId,
    LocalDate fromDate,
    LocalDate toDate
) {}
