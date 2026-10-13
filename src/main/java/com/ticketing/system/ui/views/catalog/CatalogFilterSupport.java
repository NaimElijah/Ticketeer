package com.ticketing.system.ui.views.catalog;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

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

    public static final List<String> CATEGORIES = List.of(
            CAT_ALL, "Concerts", "Sports", "Theatre", "Festivals", "Comedy");

    /** A resolved from/to date range for a date-range preset; either bound may be null (unbounded). */
    public record DateWindow(LocalDate from, LocalDate to) { }

    /** UI category label → EventCategory enum name (null = no filter). */
    public static String categoryFilterValue(String label) {
        if (label == null) return null;
        return switch (label) {
            case "Concerts"  -> "CONCERT";
            case "Sports"    -> "SPORTS";
            case "Theatre"   -> "THEATER";
            case "Festivals" -> "FESTIVAL";
            case "Comedy"    -> "COMEDY";
            default          -> null;
        };
    }

    /** Date-range preset → concrete [from, to] window relative to {@code today} (null bounds = open). */
    public static DateWindow dateWindow(String preset, LocalDate today) {
        if (preset == null) return new DateWindow(null, null);
        return switch (preset) {
            case "Today" -> new DateWindow(today, today);
            case "This weekend" -> {
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
