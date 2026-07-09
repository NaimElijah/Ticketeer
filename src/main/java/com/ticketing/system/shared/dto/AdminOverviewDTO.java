package com.ticketing.system.shared.dto;

/**
 * Read-side snapshot for the admin workspace landing page (AdminDashboardView, #279).
 *
 * <p>Platform-wide headline counters: active production companies, live (ON_SALE)
 * events, open (non-closed) complaints, and total non-refunded revenue over the
 * trailing 30 days.
 */
public record AdminOverviewDTO(
    int activeCompanies,
    int liveEvents,
    int openComplaints,
    double revenue30d
) {}
