package com.ticketing.system.shared.dto;

/**
 * Read-side snapshot for the admin workspace landing page (AdminDashboardView, upstream#279).
 *
 * <p>Platform-wide headline counters: active production companies, live (ON_SALE)
 * events, open (non-closed) complaints, and total non-refunded revenue over the
 * trailing 30 days.
 *
 * @param openComplaints complaints whose status is neither {@code CLOSED} nor {@code RESOLVED}
 * @param revenue30d     the net amount of the receipts bought in the last 30 days, refunded lines left out
 */
public record AdminOverviewDTO(
    int activeCompanies,
    int liveEvents,
    int openComplaints,
    double revenue30d
) {}
