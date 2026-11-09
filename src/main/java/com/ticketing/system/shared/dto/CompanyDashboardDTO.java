package com.ticketing.system.shared.dto;

/**
 * Owner-workspace dashboard counters for one company (V2-WIRE-OWNER-DASH), returned by
 * {@code CompanyAnalyticsService.dashboard}.
 *
 * <p>The service withholds a figure the caller may not see: {@code revenue30d} and
 * {@code ticketsSold30d} are {@code null} without VIEW_SALES, and {@code openInquiries} is
 * {@code null} without RESPOND_TO_INQUIRIES (#22).
 *
 * @param activeEvents   the company's ON_SALE events
 * @param ticketsSold30d the company's ticket lines that are not refunded, on receipts from the
 *                       trailing 30 days (#21)
 * @param revenue30d     the prices of those lines
 * @param openInquiries  the INQUIRY conversations that are not closed or resolved, with the company as
 *                       the counterparty
 * @param rating         the mean of the ratings of the company's events; {@code null} when none of its
 *                       events is rated
 */
public record CompanyDashboardDTO(
    int activeEvents,
    Integer ticketsSold30d,
    Double revenue30d,
    Integer openInquiries,
    Double rating
) {}
