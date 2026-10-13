package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.ticketing.system.ui.views.admin.SalesDateRange;

/** Window math for the Company Sales date filter (pure; no Vaadin). */
class SalesDateRangeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 30); // Q2

    @Test
    void allTimeAndUnknownAndNull_haveNoLowerBound() {
        assertNull(SalesDateRange.startOf("All time", TODAY));
        assertNull(SalesDateRange.startOf(null, TODAY));
        assertNull(SalesDateRange.startOf("nonsense", TODAY));
    }

    @Test
    void last7Days_isSevenDaysBack() {
        assertEquals(TODAY.minusDays(7).atStartOfDay(), SalesDateRange.startOf("Last 7 days", TODAY));
    }

    @Test
    void last30Days_excludesA45DayOldOrderButKeepsRecentOnes() {
        LocalDateTime from = SalesDateRange.startOf("Last 30 days", TODAY);
        assertNotNull(from);
        assertTrue(TODAY.minusDays(45).atStartOfDay().isBefore(from), "45-day-old purchase is excluded");
        assertFalse(TODAY.minusDays(10).atStartOfDay().isBefore(from), "10-day-old purchase is kept");
    }

    @Test
    void thisQuarter_startsOnFirstDayOfTheQuarter() {
        assertEquals(LocalDate.of(2026, 4, 1).atStartOfDay(), SalesDateRange.startOf("This quarter", TODAY));
    }

    @Test
    void thisYear_startsOnJanuaryFirst() {
        assertEquals(LocalDate.of(2026, 1, 1).atStartOfDay(), SalesDateRange.startOf("This year", TODAY));
    }
}
