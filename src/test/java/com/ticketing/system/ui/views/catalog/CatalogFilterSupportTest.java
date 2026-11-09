package com.ticketing.system.ui.views.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure filter-mapping helpers of {@link CatalogFilterSupport} (upstream#271) — the UI
 * category label → enum-name mapping and the date-range preset → concrete window. Shared by the
 * browse and owner event-list filters; needs no Vaadin/Spring.
 */
class CatalogFilterSupportTest {

    @Test
    void GivenCategoryLabels_WhenCategoryFilterValue_ThenMapsLabelsToEnumNames() {
        assertEquals("CONCERT",  CatalogFilterSupport.categoryFilterValue("Concerts"));
        assertEquals("SPORTS",   CatalogFilterSupport.categoryFilterValue("Sports"));
        assertEquals("THEATER",  CatalogFilterSupport.categoryFilterValue("Theatre"));
        assertEquals("FESTIVAL", CatalogFilterSupport.categoryFilterValue("Festivals"));
        assertEquals("COMEDY",   CatalogFilterSupport.categoryFilterValue("Comedy"));
    }

    @Test
    void GivenAllOrUnknownOrNull_WhenCategoryFilterValue_ThenIsNull() {
        assertNull(CatalogFilterSupport.categoryFilterValue("All categories"));
        assertNull(CatalogFilterSupport.categoryFilterValue("Nonsense"));
        assertNull(CatalogFilterSupport.categoryFilterValue(null));
    }

    @Test
    void GivenAnyTimeOrNull_WhenDateWindow_ThenIsOpen() {
        assertEquals(new CatalogFilterSupport.DateWindow(null, null),
                CatalogFilterSupport.dateWindow("Any time", LocalDate.of(2026, 6, 24)));
        assertEquals(new CatalogFilterSupport.DateWindow(null, null),
                CatalogFilterSupport.dateWindow(null, LocalDate.of(2026, 6, 24)));
    }

    @Test
    void GivenRelativePresets_WhenDateWindow_ThenResolveAgainstToday() {
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
    void GivenThisWeekendOnAWednesday_WhenDateWindow_ThenIsUpcomingSaturdayToSunday() {
        LocalDate today = LocalDate.of(2026, 6, 24); // a Wednesday

        assertEquals(new CatalogFilterSupport.DateWindow(LocalDate.of(2026, 6, 27), LocalDate.of(2026, 6, 28)),
                CatalogFilterSupport.dateWindow("This weekend", today));
    }

    @Test
    void GivenThisWeekendOnASaturday_WhenDateWindow_ThenIsTodayToTomorrow() {
        LocalDate saturday = LocalDate.of(2026, 6, 27);

        assertEquals(new CatalogFilterSupport.DateWindow(saturday, LocalDate.of(2026, 6, 28)),
                CatalogFilterSupport.dateWindow("This weekend", saturday));
    }

    // On a Sunday the weekend is nearly over, so "This weekend" means what is left of it, today, not the next
    // one (the owner's decision, 2026-11-07, #189).
    @Test
    void GivenThisWeekendOnASunday_WhenDateWindow_ThenIsTodayOnly() {
        LocalDate sunday = LocalDate.of(2026, 6, 28);

        assertEquals(new CatalogFilterSupport.DateWindow(sunday, sunday),
                CatalogFilterSupport.dateWindow("This weekend", sunday));
    }

    @Test
    void GivenCustomRange_WhenDateWindow_ThenIsOpenBounds() {
        // "Custom range" falls through to the default case — the view reads the
        // DatePicker values directly instead of using dateWindow().
        assertEquals(new CatalogFilterSupport.DateWindow(null, null),
                CatalogFilterSupport.dateWindow("Custom range", LocalDate.of(2026, 6, 24)));
    }

    // ── locationFilter ────────────────────────────────────────────────────────

    @Test
    void GivenCityAndCountrySelected_WhenLocationFilter_ThenReturnsCity() {
        assertEquals("Tel Aviv", CatalogFilterSupport.locationFilter("Tel Aviv", "Israel"));
    }

    @Test
    void GivenCitySelectedWithAllCountries_WhenLocationFilter_ThenReturnsCity() {
        // city takes priority — country is redundant once city is known
        assertEquals("Tel Aviv", CatalogFilterSupport.locationFilter("Tel Aviv", "All countries"));
    }

    @Test
    void GivenOnlyCountrySelected_WhenLocationFilter_ThenReturnsCountry() {
        assertEquals("Israel", CatalogFilterSupport.locationFilter("All cities", "Israel"));
    }

    @Test
    void GivenNothingSelected_WhenLocationFilter_ThenReturnsNull() {
        assertNull(CatalogFilterSupport.locationFilter("All cities", "All countries"));
    }

    // ── countryOptions / cityOptions ──────────────────────────────────────────
    // Pure prepend helpers — the data source supplies the (already distinct/sorted) values.

    @Test
    void GivenCountries_WhenCountryOptions_ThenPrependsAllCountries() {
        assertEquals(List.of("All countries", "Israel", "USA"),
                CatalogFilterSupport.countryOptions(List.of("Israel", "USA")));
    }

    @Test
    void GivenEmptyOrNullCountries_WhenCountryOptions_ThenIsJustSentinel() {
        assertEquals(List.of("All countries"), CatalogFilterSupport.countryOptions(List.of()));
        assertEquals(List.of("All countries"), CatalogFilterSupport.countryOptions(null));
    }

    @Test
    void GivenCities_WhenCityOptions_ThenPrependsAllCities() {
        assertEquals(List.of("All cities", "Tel Aviv", "Haifa"),
                CatalogFilterSupport.cityOptions(List.of("Tel Aviv", "Haifa")));
    }

    @Test
    void GivenEmptyOrNullCities_WhenCityOptions_ThenIsJustSentinel() {
        assertEquals(List.of("All cities"), CatalogFilterSupport.cityOptions(List.of()));
        assertEquals(List.of("All cities"), CatalogFilterSupport.cityOptions(null));
    }

    // ── isValidCustomRange ────────────────────────────────────────────────────

    @Test
    void GivenFromBeforeTo_WhenIsValidCustomRange_ThenIsValid() {
        assertTrue(CatalogFilterSupport.isValidCustomRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)));
    }

    @Test
    void GivenSameDay_WhenIsValidCustomRange_ThenIsValid() {
        LocalDate day = LocalDate.of(2026, 6, 15);
        assertTrue(CatalogFilterSupport.isValidCustomRange(day, day));
    }

    @Test
    void GivenFromAfterTo_WhenIsValidCustomRange_ThenIsInvalid() {
        assertFalse(CatalogFilterSupport.isValidCustomRange(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 1)));
    }

    @Test
    void GivenOpenBounds_WhenIsValidCustomRange_ThenAreValid() {
        // a single open-ended bound (or none) is a legitimate query, not an inversion
        assertTrue(CatalogFilterSupport.isValidCustomRange(null, LocalDate.of(2026, 6, 1)));
        assertTrue(CatalogFilterSupport.isValidCustomRange(LocalDate.of(2026, 6, 1), null));
        assertTrue(CatalogFilterSupport.isValidCustomRange(null, null));
    }
}
