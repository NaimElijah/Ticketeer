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
    void GivenAllTimeUnknownOrNullRange_WhenStartOf_ThenHasNoLowerBound() {
        assertNull(SalesDateRange.startOf("All time", TODAY));
        assertNull(SalesDateRange.startOf(null, TODAY));
        assertNull(SalesDateRange.startOf("nonsense", TODAY));
    }

    @Test
    void GivenLast7Days_WhenStartOf_ThenIsSevenDaysBack() {
        assertEquals(TODAY.minusDays(7).atStartOfDay(), SalesDateRange.startOf("Last 7 days", TODAY));
    }

    @Test
    void GivenLast30Days_WhenStartOf_ThenExcludesA45DayOldOrderButKeepsRecentOnes() {
        LocalDateTime from = SalesDateRange.startOf("Last 30 days", TODAY);
        assertNotNull(from);
        assertTrue(TODAY.minusDays(45).atStartOfDay().isBefore(from), "45-day-old purchase is excluded");
        assertFalse(TODAY.minusDays(10).atStartOfDay().isBefore(from), "10-day-old purchase is kept");
    }

    @Test
    void GivenThisQuarter_WhenStartOf_ThenStartsOnFirstDayOfTheQuarter() {
        assertEquals(LocalDate.of(2026, 4, 1).atStartOfDay(), SalesDateRange.startOf("This quarter", TODAY));
    }

    @Test
    void GivenThisYear_WhenStartOf_ThenStartsOnJanuaryFirst() {
        assertEquals(LocalDate.of(2026, 1, 1).atStartOfDay(), SalesDateRange.startOf("This year", TODAY));
    }
}
