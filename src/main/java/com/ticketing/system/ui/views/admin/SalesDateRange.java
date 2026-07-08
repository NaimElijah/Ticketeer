package com.ticketing.system.ui.views.admin;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Date-range presets for the Company Sales History filter. The filter narrows orders by their
 * purchase date. Pure (no Vaadin) so the window math is unit-testable.
 */
public final class SalesDateRange {

    public static final String ALL_TIME = "All time";

    public static final List<String> OPTIONS = List.of(
            "Last 7 days", "Last 30 days", "This quarter", "This year", ALL_TIME);

    private SalesDateRange() { }

    /**
     * Inclusive lower bound for {@code range} relative to {@code today}; {@code null} for
     * "All time" (and any unknown value), meaning no lower bound. An order is in range when its
     * purchase time is not before the returned bound.
     */
    public static LocalDateTime startOf(String range, LocalDate today) {
        if (range == null) {
            return null;
        }
        return switch (range) {
            case "Last 7 days"  -> today.minusDays(7).atStartOfDay();
            case "Last 30 days" -> today.minusDays(30).atStartOfDay();
            case "This quarter" -> firstDayOfQuarter(today).atStartOfDay();
            case "This year"    -> today.withDayOfYear(1).atStartOfDay();
            default              -> null; // All time / unknown → unbounded
        };
    }

    private static LocalDate firstDayOfQuarter(LocalDate today) {
        int firstMonthOfQuarter = ((today.getMonthValue() - 1) / 3) * 3 + 1;
        return LocalDate.of(today.getYear(), firstMonthOfQuarter, 1);
    }
}
