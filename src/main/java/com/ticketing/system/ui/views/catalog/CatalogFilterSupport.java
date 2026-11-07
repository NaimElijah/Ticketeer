package com.ticketing.system.ui.views.catalog;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.ticketing.system.ui.support.EventCategoryLabels;

/**
 * Shared, layout-independent options and pure helpers for the catalogue event filters — the single
 * source of truth used by both the buyer browse page ({@link BrowseEventsView}) and the owner event
 * list ({@code CompanyEventListView}). Holds the filter option lists and the label→value mappings;
 * each view builds its own controls and lays them out as it sees fit.
 */
public final class CatalogFilterSupport {

    private CatalogFilterSupport() { }

    public static final String CAT_ALL     = "All categories";
    public static final String COUNTRY_ALL = "All countries";
    public static final String CITY_ALL    = "All cities";
    public static final String DATE_ANY    = "Any time";

    // The placeholder and the accessible name of a range's lower and upper bound, and of a custom date
    // range's two pickers. A field's name holds its visible text (#188, WCAG 2.5.3), and the named group
    // around a range supplies what it bounds, so a screen reader says "Price range, Min".
    public static final String BOUND_MIN   = "Min";
    public static final String BOUND_MAX   = "Max";
    public static final String CUSTOM_FROM = "Start date";
    public static final String CUSTOM_TO   = "End date";

    /** The category filter's options: {@link #CAT_ALL}, then every category's label ({@link EventCategoryLabels}). */
    public static final List<String> CATEGORIES =
            Stream.concat(Stream.of(CAT_ALL), EventCategoryLabels.labels().stream()).toList();

    /** A resolved from/to date range for a date-range preset; either bound may be null (unbounded). */
    public record DateWindow(LocalDate from, LocalDate to) { }

    /** UI category label → EventCategory enum name (null = no filter, for {@link #CAT_ALL} or any other text). */
    public static String categoryFilterValue(String label) {
        return EventCategoryLabels.categoryFor(label).map(Enum::name).orElse(null);
    }

    /** Date-range preset → concrete [from, to] window relative to {@code today} (null bounds = open). */
    public static DateWindow dateWindow(String preset, LocalDate today) {
        if (preset == null) return new DateWindow(null, null);
        return switch (preset) {
            case "Today" -> new DateWindow(today, today);
            // On a Sunday the weekend is today alone, not the next one (the owner's decision, #189).
            case "This weekend" -> {
                if (today.getDayOfWeek() == DayOfWeek.SUNDAY) yield new DateWindow(today, today);
                LocalDate sat = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
                yield new DateWindow(sat, sat.plusDays(1));
            }
            case "Next 7 days"   -> new DateWindow(today, today.plusDays(7));
            case "Next 30 days"  -> new DateWindow(today, today.plusDays(30));
            case "This month"    -> new DateWindow(today, today.with(TemporalAdjusters.lastDayOfMonth()));
            case "Next 3 months" -> new DateWindow(today, today.plusMonths(3));
            default              -> new DateWindow(null, null); // "Any time" and "Custom range"
        };
    }

    /** City (if selected) beats country so the DB predicate is as narrow as possible. */
    public static String locationFilter(String filterCity, String filterCountry) {
        if (!CITY_ALL.equals(filterCity))       return filterCity;
        if (!COUNTRY_ALL.equals(filterCountry)) return filterCountry;
        return null;
    }

    /** Country select options: COUNTRY_ALL first, then the supplied countries (caller supplies them distinct/sorted). */
    public static List<String> countryOptions(List<String> countries) {
        List<String> opts = new ArrayList<>();
        opts.add(COUNTRY_ALL);
        if (countries != null) opts.addAll(countries);
        return opts;
    }

    /** City select options: CITY_ALL first, then the supplied cities (caller supplies them distinct/sorted). */
    public static List<String> cityOptions(List<String> cities) {
        List<String> opts = new ArrayList<>();
        opts.add(CITY_ALL);
        if (cities != null) opts.addAll(cities);
        return opts;
    }

    /** A custom range is searchable unless it's the from&gt;to inversion; an open bound (null) is fine. */
    public static boolean isValidCustomRange(LocalDate from, LocalDate to) {
        return from == null || to == null || !from.isAfter(to);
    }
}
