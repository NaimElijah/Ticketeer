package com.ticketing.system.shared.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Input to {@code SystemAdminService.viewGlobalHistory} (UC-31): the admin's purchase-history filters
 * per II.6.4, by buyer, production company or specific event. Every component may be {@code null},
 * which means no filter on it.
 *
 * @param buyerUserId the member who bought
 * @param companyId   a production company; narrows {@code eventIds} to that company's events, and
 *                    means all of them when {@code eventIds} is {@code null}
 * @param eventIds    the events to include
 * @param fromDate    inclusive lower bound on the purchase day, in UTC
 * @param toDate      inclusive upper bound on the purchase day, in UTC
 */
public record GlobalHistoryFiltersDTO(
    Integer buyerUserId,
    Integer companyId,
    List<Integer> eventIds,
    LocalDate fromDate,
    LocalDate toDate
) {}
