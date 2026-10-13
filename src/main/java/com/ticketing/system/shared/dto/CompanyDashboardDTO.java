package com.ticketing.system.shared.dto;

// Owner-workspace dashboard counters for a single company (V2-WIRE-OWNER-DASH).
// revenue30d / ticketsSold30d are the company's share of non-refunded receipts in the
// trailing 30 days; activeEvents counts ON_SALE events; openInquiries counts unresolved
// INQUIRY conversations where the company is the counterparty. rating is DERIVED — the mean
// of the company's events' ratings (null when none of its events are rated).
// revenue30d is null when the caller lacks VIEW_SALES and openInquiries is null when the
// caller lacks RESPOND_TO_INQUIRIES: the service withholds them at the boundary (#22).
public record CompanyDashboardDTO(
    int activeEvents,
    int ticketsSold30d,
    Double revenue30d,
    Integer openInquiries,
    Double rating
) {}
