package com.ticketing.system.ui.views.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure filter-mapping helpers of {@link CatalogFilterSupport} (#271) — the UI
 * category label → enum-name mapping and the date-range preset → concrete window. Shared by the
 * browse and owner event-list filters; needs no Vaadin/Spring.
 */
class BrowseEventsFilterTest {

    @Test
    void categoryFilterValue_mapsLabelsToEnumNames() {
        assertEquals("CONCERT",  CatalogFilterSupport.categoryFilterValue("Concerts"));
        assertEquals("SPORTS",   CatalogFilterSupport.categoryFilterValue("Sports"));
        assertEquals("THEATER",  CatalogFilterSupport.categoryFilterValue("Theatre"));
        assertEquals("FESTIVAL", CatalogFilterSupport.categoryFilterValue("Festivals"));
        assertEquals("COMEDY",   CatalogFilterSupport.categoryFilterValue("Comedy"));
    }

    @Test
    void categoryFilterValue_allOrUnknownOrNull_isNull() {
        assertNull(CatalogFilterSupport.categoryFilterValue("All categories"));
        assertNull(CatalogFilterSupport.categoryFilterValue("Nonsense"));
        assertNull(CatalogFilterSupport.categoryFilterValue(null));
    }

    @Test
    void dateWindow_anyTimeOrNull_isOpen() {
        assertEquals(new CatalogFilterSupport.DateWindow(null, null),
                CatalogFilterSupport.dateWindow("Any time", LocalDate.of(2026, 6, 24)));
        assertEquals(new CatalogFilterSupport.DateWindow(null, null),
                CatalogFilterSupport.dateWindow(null, LocalDate.of(2026, 6, 24)));
    }

    @Test
    void dateWindow_relativePresets_resolveAgainstToday() {
        LocalDate today = LocalDate.of(2026, 6, 24);
        assertEquals(new CatalogFilterSupport.DateWindow(today, today),
                CatalogFilterSupport.dateWindow("Today", today));
        assertEquals(new CatalogFilterSupport.DateWindow(today, today.plusDays(7)),
                CatalogFilterSupport.dateWindow("Next 7 days", today));
        assertEquals(new CatalogFilterSupport.DateWindow(today, today.plusDays(30)),
                CatalogFilterSupport.dateWindow("Next 30 days", today));
        assertEquals(new CatalogFilterSupport.DateWindow(today, today.plusMonths(3)),
                CatalogFilterSupport.dateWindow("Next 3 months", today));
        assertEquals(new CatalogFilterSupport.DateWindow(today, LocalDate.of(2026, 6, 30)),
                CatalogFilterSupport.dateWindow("This month", today));
    }

    @Test
    void dateWindow_thisWeekend_isUpcomingSaturdayToSunday() {
        LocalDate today = LocalDate.of(2026, 6, 24); // a Wednesday
        CatalogFilterSupport.DateWindow w = CatalogFilterSupport.dateWindow("This weekend", today);
        assertEquals(DayOfWeek.SATURDAY, w.from().getDayOfWeek());
        assertEquals(w.from().plusDays(1), w.to());          // Saturday → Sunday
        assertFalse(w.from().isBefore(today));               // upcoming, not in the past
    }

    @Test
    void dateWindow_customRange_isOpenBounds() {
        // "Custom range" falls through to the default case — the view reads the
        // DatePicker values directly instead of using dateWindow().
        assertEquals(new CatalogFilterSupport.DateWindow(null, null),
                CatalogFilterSupport.dateWindow("Custom range", LocalDate.of(2026, 6, 24)));
    }

    // ── locationFilter ────────────────────────────────────────────────────────

    @Test
    void locationFilter_citySelected_returnsCity() {
        assertEquals("Tel Aviv", CatalogFilterSupport.locationFilter("Tel Aviv", "Israel"));
    }

    @Test
    void locationFilter_citySelectedIgnoresCountry() {
        // city takes priority — country is redundant once city is known
        assertEquals("Tel Aviv", CatalogFilterSupport.locationFilter("Tel Aviv", "All countries"));
    }

    @Test
    void locationFilter_onlyCountrySelected_returnsCountry() {
        assertEquals("Israel", CatalogFilterSupport.locationFilter("All cities", "Israel"));
    }

    @Test
    void locationFilter_nothingSelected_returnsNull() {
        assertNull(CatalogFilterSupport.locationFilter("All cities", "All countries"));
    }

    // ── countryOptions / cityOptions ──────────────────────────────────────────
    // Pure prepend helpers — the data source supplies the (already distinct/sorted) values.

    @Test
    void countryOptions_prependsAllCountries() {
        assertEquals(List.of("All countries", "Israel", "USA"),
                CatalogFilterSupport.countryOptions(List.of("Israel", "USA")));
    }

    @Test
    void countryOptions_emptyOrNull_isJustSentinel() {
        assertEquals(List.of("All countries"), CatalogFilterSupport.countryOptions(List.of()));
        assertEquals(List.of("All countries"), CatalogFilterSupport.countryOptions(null));
    }

    @Test
    void cityOptions_prependsAllCities() {
        assertEquals(List.of("All cities", "Tel Aviv", "Haifa"),
                CatalogFilterSupport.cityOptions(List.of("Tel Aviv", "Haifa")));
    }

    @Test
    void cityOptions_emptyOrNull_isJustSentinel() {
        assertEquals(List.of("All cities"), CatalogFilterSupport.cityOptions(List.of()));
        assertEquals(List.of("All cities"), CatalogFilterSupport.cityOptions(null));
    }

    // ── isValidCustomRange ────────────────────────────────────────────────────

    @Test
    void isValidCustomRange_fromBeforeTo_isValid() {
        assertTrue(CatalogFilterSupport.isValidCustomRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)));
    }

    @Test
    void isValidCustomRange_sameDay_isValid() {
        LocalDate day = LocalDate.of(2026, 6, 15);
        assertTrue(CatalogFilterSupport.isValidCustomRange(day, day));
    }

    @Test
    void isValidCustomRange_fromAfterTo_isInvalid() {
        assertFalse(CatalogFilterSupport.isValidCustomRange(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 1)));
    }

    @Test
    void isValidCustomRange_openBounds_areValid() {
        // a single open-ended bound (or none) is a legitimate query, not an inversion
        assertTrue(CatalogFilterSupport.isValidCustomRange(null, LocalDate.of(2026, 6, 1)));
        assertTrue(CatalogFilterSupport.isValidCustomRange(LocalDate.of(2026, 6, 1), null));
        assertTrue(CatalogFilterSupport.isValidCustomRange(null, null));
    }
}
