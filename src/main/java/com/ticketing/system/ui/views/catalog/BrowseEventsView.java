package com.ticketing.system.ui.views.catalog;

import com.ticketing.system.shared.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.shared.dto.EventSummaryDTO;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkCol;
import com.ticketing.system.ui.components.kit.LkDateRangeField;
import com.ticketing.system.ui.components.kit.LkGrid;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkRow;
import com.ticketing.system.ui.components.kit.LkSelect;
import com.ticketing.system.ui.components.kit.LkStatusDot;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.presenters.catalog.BrowseEventsPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

@Route(value = "browse", layout = MainLayout.class)
@PageTitle("Browse Events · TicketHub")
@AnonymousAllowed
public class BrowseEventsView extends LkPage implements BeforeEnterObserver {

    // ---- data model ----

    private record EventRow(String name, String category, String venue,
            String date, LocalDateTime startsAt, int priceCents,
            LkStatusDot.Tone tone, String status, String id,
            Double rating, List<String> artists) {
    }

    private final BrowseEventsPresenter presenter;
    private final SessionIdentity identity;

    // Filter option lists + label→value mappings live in CatalogFilterSupport (shared with the owner
    // event list); these aliases keep the in-view references terse.
    private static final String CAT_ALL     = CatalogFilterSupport.CAT_ALL;
    private static final String COUNTRY_ALL = CatalogFilterSupport.COUNTRY_ALL;
    private static final String CITY_ALL    = CatalogFilterSupport.CITY_ALL;
    private static final String DATE_ANY    = CatalogFilterSupport.DATE_ANY;

    private static final List<String> CATEGORIES = CatalogFilterSupport.CATEGORIES;

    private static final List<String> SORTS = List.of(
            "Date · soonest", "Price · low to high", "Price · high to low",
            "Rating · high to low", "Rating · low to high");

    // ---- filter state ----

    private String filterCategory  = CAT_ALL;
    private String filterCountry   = COUNTRY_ALL;
    private String filterCity      = CITY_ALL;
    private String filterArtist    = "";
    private String filterEventName = "";
    private String filterKeywords  = "";
    private String filterDateRange = DATE_ANY;
    private String filterSort      = SORTS.get(0);
    private Integer filterPriceMin = null;
    private Integer filterPriceMax = null;
    private Double filterMinEventRating   = null;
    private Double filterMaxEventRating   = null;
    private Double filterMinCompanyRating = null;
    private Double filterMaxCompanyRating = null;

    // ---- ui references for re-render / reset ----

    private LkSelect categorySelect;
    private LkSelect countrySelect;
    private LkSelect citySelect;
    private LkSelect sortSelect;
    private LkDateRangeField dateRangeField;
    private DatePicker customFromPicker;
    private DatePicker customToPicker;
    private Div customRangeRow;
    private TextField artistField;
    private TextField eventNameField;
    private TextField keywordsField;
    private IntegerField priceMin;
    private IntegerField priceMax;
    private NumberField eventRatingMin;
    private NumberField eventRatingMax;
    private NumberField companyRatingMin;
    private NumberField companyRatingMax;
    private Div gridSlot;
    private Span resultsCountSpan;

    public BrowseEventsView(BrowseEventsPresenter presenter, SessionIdentity identity) {
        this.presenter = presenter;
        this.identity = identity;

        List<EventRow> allEvents = presenter.search(identity.credential(), CatalogSearchFiltersDTO.empty())
                .stream().map(BrowseEventsView::toRow).toList();

        add(buildHero());
        add(buildAllEventsHeader());
        add(buildBrowseSplit());
        renderGrid(allEvents.stream().sorted(comparatorFor(filterSort)).toList());
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        List<String> values = event.getLocation().getQueryParameters()
            .getParameters().getOrDefault("category", List.of());
        if (values.isEmpty()) return;
        String category = values.get(0);
        if (CATEGORIES.contains(category)) setCategory(category);
    }

    // ---- hero ----

    private Component buildHero() {
        Div hero = new Div();
        hero.addClassName("bz-hero");
        Div title = new Div();
        title.addClassName("bz-hero-title");
        title.setText("Find your next experience");
        Div sub = new Div();
        sub.addClassName("bz-hero-sub");
        sub.setText("Concerts, matches, theatre and conferences — across Israel.");
        hero.add(title, sub);
        return hero;
    }

    // ---- category selection ----

    private void setCategory(String category) {
        if (filterCategory.equals(category)) return;
        filterCategory = category;
        if (categorySelect != null) categorySelect.setValue(category);
        runSearch();
    }

    // ---- "All events" header with live result count ----

    private Component buildAllEventsHeader() {
        LkRow row = new LkRow().align("baseline").gap(10);
        row.add(Lk.h2("All Events"));
        resultsCountSpan = Lk.muted("");
        resultsCountSpan.getStyle().set("font-size", "13.5px");
        row.add(resultsCountSpan);
        return row;
    }

    // ---- filter sidebar + grid slot ----

    private Component buildBrowseSplit() {
        Div split = new Div();
        split.addClassName("bz-browse-split");
        split.add(buildFilters(), buildGridSlot());
        return split;
    }

    private Component buildFilters() {
        LkCard card = new LkCard("Filters").pad(18).flush();

        NativeButton clear = new NativeButton("Clear All");
        clear.addClassName("lk-link-btn");
        clear.addClickListener(e -> clearFilters());
        card.headerRight(clear);

        // Category
        categorySelect = new LkSelect(CAT_ALL, CATEGORIES).label("Category");
        categorySelect.onChange(this::setCategory);

        // Event name — case-insensitive substring on the event name.
        eventNameField = new TextField();
        eventNameField.setPlaceholder("e.g. Coldplay");
        eventNameField.setValueChangeMode(ValueChangeMode.LAZY);
        eventNameField.getStyle().set("width", "100%");
        eventNameField.addValueChangeListener(e -> {
            String v = e.getValue() == null ? "" : e.getValue();
            if (Objects.equals(filterEventName, v)) return;
            filterEventName = v;
            runSearch();
        });
        Div eventNameWrap = labelledWrap("Event name", eventNameField);

        // Keywords — matches the event name OR any artist in the line-up.
        keywordsField = new TextField();
        keywordsField.setPlaceholder("Search name or artist");
        keywordsField.setValueChangeMode(ValueChangeMode.LAZY);
        keywordsField.getStyle().set("width", "100%");
        keywordsField.addValueChangeListener(e -> {
            String v = e.getValue() == null ? "" : e.getValue();
            if (Objects.equals(filterKeywords, v)) return;
            filterKeywords = v;
            runSearch();
        });
        Div keywordsWrap = labelledWrap("Keywords", keywordsField);

        // Artist
        artistField = new TextField();
        artistField.setPlaceholder("e.g. Taylor Swift");
        artistField.setValueChangeMode(ValueChangeMode.LAZY);
        artistField.getStyle().set("width", "100%");
        artistField.addValueChangeListener(e -> {
            String v = e.getValue() == null ? "" : e.getValue();
            if (Objects.equals(filterArtist, v)) return;
            filterArtist = v;
            runSearch();
        });
        Div artistWrap = labelledWrap("Artist", artistField);

        // Date range — "Custom range" preset reveals the pickers below
        dateRangeField = new LkDateRangeField().label("Date Range");
        dateRangeField.onChange(v -> {
            if (Objects.equals(filterDateRange, v)) return;
            filterDateRange = v;
            customRangeRow.setVisible("Custom range".equals(v));
            // Returning to "Custom range" with the pickers already populated must re-query;
            // every other preset queries immediately.
            if ("Custom range".equals(v)) runCustomSearchIfValid();
            else runSearch();
        });

        // Custom date pickers (shown only when "Custom range" is selected). Each picker bounds the
        // other (min/max) so an inverted range can't be entered, then re-queries — clearing a date
        // re-queries with that bound left open rather than leaving the grid stale.
        customFromPicker = new DatePicker();
        customFromPicker.setPlaceholder("Start date");
        customFromPicker.addValueChangeListener(e -> {
            customToPicker.setMin(e.getValue());   // null clears the constraint
            runCustomSearchIfValid();
        });

        customToPicker = new DatePicker();
        customToPicker.setPlaceholder("End date");
        customToPicker.addValueChangeListener(e -> {
            customFromPicker.setMax(e.getValue());
            runCustomSearchIfValid();
        });

        customRangeRow = new Div();
        customRangeRow.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "8px");
        customFromPicker.getStyle().set("width", "100%");
        customToPicker.getStyle().set("width", "100%");
        customRangeRow.add(customFromPicker, customToPicker);
        customRangeRow.setVisible(false);

        // Country → City (dependent selects), both populated from events that actually exist.
        countrySelect = new LkSelect(COUNTRY_ALL,
                CatalogFilterSupport.countryOptions(presenter.countries())).label("Country");
        countrySelect.onChange(v -> {
            if (Objects.equals(filterCountry, v)) return;
            filterCountry = v;
            filterCity = CITY_ALL;
            citySelect.setOptions(COUNTRY_ALL.equals(v)
                    ? List.of(CITY_ALL)
                    : CatalogFilterSupport.cityOptions(presenter.cities(v)));
            citySelect.enabled(!COUNTRY_ALL.equals(v));
            runSearch();
        });

        citySelect = new LkSelect(CITY_ALL, List.of(CITY_ALL)).label("City");
        citySelect.enabled(false);
        citySelect.onChange(v -> {
            if (Objects.equals(filterCity, v)) return;
            filterCity = v;
            runSearch();
        });

        // Sort
        sortSelect = new LkSelect(SORTS.get(0), SORTS).label("Sort By");
        sortSelect.onChange(v -> {
            if (Objects.equals(filterSort, v)) return;
            filterSort = v;
            runSearch();
        });

        LkCol col = new LkCol().gap(16);
        col.add(sortSelect, categorySelect, eventNameWrap, keywordsWrap, artistWrap, dateRangeField, customRangeRow,
                buildPriceRange(), buildEventRatingRange(), buildCompanyRatingRange(),
                countrySelect, citySelect);
        card.add(col);
        return card;
    }

    private Component buildPriceRange() {
        priceMin = new IntegerField();
        priceMin.setPlaceholder("Min");
        priceMin.setMin(0);
        priceMin.setMax(1000);
        priceMin.setStep(10);
        priceMin.setPrefixComponent(new Span("$"));
        priceMin.setValueChangeMode(ValueChangeMode.LAZY);
        priceMin.getStyle().set("flex", "1 1 0");
        priceMin.addValueChangeListener(e -> {
            if (Objects.equals(filterPriceMin, e.getValue())) return;
            filterPriceMin = e.getValue();
            runSearch();
        });

        priceMax = new IntegerField();
        priceMax.setPlaceholder("Max");
        priceMax.setMin(0);
        priceMax.setMax(1000);
        priceMax.setStep(10);
        priceMax.setPrefixComponent(new Span("$"));
        priceMax.setValueChangeMode(ValueChangeMode.LAZY);
        priceMax.getStyle().set("flex", "1 1 0");
        priceMax.addValueChangeListener(e -> {
            if (Objects.equals(filterPriceMax, e.getValue())) return;
            filterPriceMax = e.getValue();
            runSearch();
        });

        Div wrap = new Div();
        Span label = new Span("Price range");
        label.addClassName("lk-label");
        label.getStyle().set("display", "block").set("margin-bottom", "6px");
        wrap.add(label);
        LkRow row = new LkRow().gap(8);
        row.add(priceMin, priceMax);
        wrap.add(row);
        return wrap;
    }

    private Component buildEventRatingRange() {
        eventRatingMin = ratingField("Min", v -> {
            if (Objects.equals(filterMinEventRating, v)) return;
            filterMinEventRating = v;
            runSearch();
        });
        eventRatingMax = ratingField("Max", v -> {
            if (Objects.equals(filterMaxEventRating, v)) return;
            filterMaxEventRating = v;
            runSearch();
        });
        return ratingRangeWrap("Event rating", eventRatingMin, eventRatingMax);
    }

    private Component buildCompanyRatingRange() {
        companyRatingMin = ratingField("Min", v -> {
            if (Objects.equals(filterMinCompanyRating, v)) return;
            filterMinCompanyRating = v;
            runSearch();
        });
        companyRatingMax = ratingField("Max", v -> {
            if (Objects.equals(filterMaxCompanyRating, v)) return;
            filterMaxCompanyRating = v;
            runSearch();
        });
        return ratingRangeWrap("Organizer rating", companyRatingMin, companyRatingMax);
    }

    /** A 0–5 rating bound input (NumberField, half-star step) wired to {@code onChange}. */
    private static NumberField ratingField(String placeholder, Consumer<Double> onChange) {
        NumberField f = new NumberField();
        f.setPlaceholder(placeholder);
        f.setMin(0);
        f.setMax(5);
        f.setStep(0.5);
        f.setValueChangeMode(ValueChangeMode.LAZY);
        f.getStyle().set("flex", "1 1 0");
        f.addValueChangeListener(e -> onChange.accept(e.getValue()));
        return f;
    }

    private static Component ratingRangeWrap(String labelText, NumberField min, NumberField max) {
        Div wrap = new Div();
        Span label = new Span(labelText);
        label.addClassName("lk-label");
        label.getStyle().set("display", "block").set("margin-bottom", "6px");
        wrap.add(label);
        LkRow row = new LkRow().gap(8);
        row.add(min, max);
        wrap.add(row);
        return wrap;
    }

    private Component buildGridSlot() {
        gridSlot = new Div();
        gridSlot.getStyle().set("min-width", "0").set("width", "100%");
        return gridSlot;
    }

    // ---- reset ----

    private void clearFilters() {
        // Reset state first — onChange handlers short-circuit when value == filterXxx.
        filterCategory  = CAT_ALL;
        filterCountry   = COUNTRY_ALL;
        filterCity      = CITY_ALL;
        filterArtist    = "";
        filterEventName = "";
        filterKeywords  = "";
        filterDateRange = DATE_ANY;
        filterSort      = SORTS.get(0);
        filterPriceMin  = null;
        filterPriceMax  = null;
        filterMinEventRating   = null;
        filterMaxEventRating   = null;
        filterMinCompanyRating = null;
        filterMaxCompanyRating = null;

        categorySelect.setValue(CAT_ALL);
        countrySelect.setValue(COUNTRY_ALL);
        citySelect.setOptions(List.of(CITY_ALL));
        citySelect.enabled(false);
        artistField.clear();
        eventNameField.clear();
        keywordsField.clear();
        dateRangeField.setValue(DATE_ANY);
        customRangeRow.setVisible(false);
        customFromPicker.clear();
        customToPicker.clear();
        sortSelect.setValue(SORTS.get(0));
        priceMin.clear();
        priceMax.clear();
        eventRatingMin.clear();
        eventRatingMax.clear();
        companyRatingMin.clear();
        companyRatingMax.clear();
        runSearch();
    }

    // ---- server-side search + render ----

    private void runSearch() {
        List<EventRow> rows = presenter.search(identity.credential(), currentFilters()).stream()
                .map(BrowseEventsView::toRow)
                .sorted(comparatorFor(filterSort))
                .toList();
        renderGrid(rows);
    }

    /**
     * Re-query for the custom-range flow: only while "Custom range" is the active preset, and only
     * when the picker values aren't inverted (an inverted range is rejected server-side and would
     * surface as a confusing empty grid). Either bound may be open (null).
     */
    private void runCustomSearchIfValid() {
        if (!"Custom range".equals(filterDateRange)) return;
        if (CatalogFilterSupport.isValidCustomRange(customFromPicker.getValue(), customToPicker.getValue()))
            runSearch();
    }

    private CatalogSearchFiltersDTO currentFilters() {
        LocalDate from, to;
        if ("Custom range".equals(filterDateRange)) {
            from = customFromPicker.getValue();
            to   = customToPicker.getValue();
        } else {
            CatalogFilterSupport.DateWindow dw = CatalogFilterSupport.dateWindow(filterDateRange, LocalDate.now());
            from = dw.from();
            to   = dw.to();
        }
        return new CatalogSearchFiltersDTO(
                filterEventName.isBlank() ? null : filterEventName,
                filterArtist.isBlank() ? null : filterArtist,
                CatalogFilterSupport.categoryFilterValue(filterCategory),
                filterKeywords.isBlank() ? null : filterKeywords,
                filterPriceMin == null ? null : filterPriceMin.doubleValue(),
                filterPriceMax == null ? null : filterPriceMax.doubleValue(),
                from, to,
                locationFilter(),
                filterMinEventRating, filterMaxEventRating,
                filterMinCompanyRating, filterMaxCompanyRating);
    }

    private String locationFilter() {
        return CatalogFilterSupport.locationFilter(filterCity, filterCountry);
    }

    private void renderGrid(List<EventRow> rows) {
        gridSlot.removeAll();
        if (resultsCountSpan != null) {
            resultsCountSpan.setText(formatResultLine(rows.size()));
        }
        if (rows.isEmpty()) {
            gridSlot.add(buildEmptyState());
            return;
        }
        gridSlot.add(buildGrid(rows));
    }

    private String formatResultLine(int n) {
        StringBuilder s = new StringBuilder();
        s.append(n).append(" event").append(n == 1 ? "" : "s");
        if (!CAT_ALL.equals(filterCategory))
            s.append(" · ").append(filterCategory.toLowerCase());
        if (!filterArtist.isBlank())
            s.append(" · ").append(filterArtist);
        if (!CITY_ALL.equals(filterCity))
            s.append(" in ").append(filterCity);
        else if (!COUNTRY_ALL.equals(filterCountry))
            s.append(" in ").append(filterCountry);
        if (!DATE_ANY.equals(filterDateRange)) {
            if ("Custom range".equals(filterDateRange)
                    && customFromPicker.getValue() != null && customToPicker.getValue() != null) {
                s.append(" · ").append(customFromPicker.getValue())
                 .append(" → ").append(customToPicker.getValue());
            } else if (!"Custom range".equals(filterDateRange)) {
                s.append(" · ").append(filterDateRange.toLowerCase());
            }
        }
        if (filterPriceMin != null || filterPriceMax != null) {
            s.append(" · $")
                    .append(filterPriceMin == null ? "0" : filterPriceMin)
                    .append("–$")
                    .append(filterPriceMax == null ? "∞" : filterPriceMax);
        }
        return s.toString();
    }

    private Component buildGrid(List<EventRow> events) {
        LkGrid grid = new LkGrid()
                .col("Event", "name")
                .col("Category", "cat")
                .col("Venue", "venue")
                .col("Date", "date")
                .col("From", "from", LkGrid.Align.RIGHT)
                .col("Rating", "rating", LkGrid.Align.RIGHT)
                .col("Status", "status")
                .col("", "act", LkGrid.Align.RIGHT);
        for (EventRow ev : events)
            addRow(grid, ev);
        grid.build();
        return grid;
    }

    private void addRow(LkGrid grid, EventRow ev) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        Span name = new Span();
        name.getElement().setProperty("innerHTML", nameCellHtml(ev));
        row.put("name", name);
        row.put("cat", ev.category);
        row.put("venue", ev.venue);
        row.put("date", ev.date);
        row.put("from", ev.priceCents > 0 ? String.format("$%,.2f", ev.priceCents / 100.0) : "—");
        row.put("rating", ev.rating == null ? "—" : "★ " + ratingText(ev.rating));
        row.put("status", new LkStatusDot(ev.tone, ev.status));
        LkBtn reserve = new LkBtn("Reserve")
                .variant(LkBtn.Variant.primary)
                .size(LkBtn.Size.s)
                .onClick(e -> UI.getCurrent().navigate("events/" + ev.id));
        row.put("act", reserve);
        grid.row(row);
    }

    private Component buildEmptyState() {
        Span empty = new Span("No events match your filters. Try widening category, region, dates, or price.");
        empty.getStyle()
                .set("display", "block").set("padding", "48px").set("text-align", "center")
                .set("color", "var(--muted)").set("background", "#fff")
                .set("border", "1px dashed var(--border-strong)").set("border-radius", "12px");
        return empty;
    }

    // ---- helpers ----

    private static Div labelledWrap(String labelText, Component field) {
        Div wrap = new Div();
        Span label = new Span(labelText);
        label.addClassName("lk-label");
        label.getStyle().set("display", "block").set("margin-bottom", "6px");
        wrap.add(label, field);
        return wrap;
    }

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMM");

    private static EventRow toRow(EventSummaryDTO dto) {
        LocalDateTime start = (dto.showDates() == null || dto.showDates().isEmpty())
                ? null : dto.showDates().get(0).startsAt();
        String date = start == null ? "TBA" : start.format(DATE_FMT);
        int priceCents = (int) Math.round(dto.minPrice() * 100);
        LkStatusDot.Tone tone = dto.soldOut() ? LkStatusDot.Tone.muted : LkStatusDot.Tone.ok;
        String status = dto.soldOut() ? "Sold out" : "On sale";
        return new EventRow(dto.name(), prettyCategory(dto.category()), dto.location(),
                date, start, priceCents, tone, status, String.valueOf(dto.eventId()),
                dto.rating(), dto.artistsNames());
    }

    private static Comparator<EventRow> comparatorFor(String sort) {
        Comparator<EventRow> bySoonest = Comparator.comparing(EventRow::startsAt,
                Comparator.nullsLast(Comparator.naturalOrder()));
        return switch (sort) {
            case "Price · low to high"  -> Comparator.comparingInt(EventRow::priceCents);
            case "Price · high to low"  -> Comparator.comparingInt(EventRow::priceCents).reversed();
            case "Rating · high to low" -> Comparator.comparing(EventRow::rating,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            case "Rating · low to high" -> Comparator.comparing(EventRow::rating,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            default                     -> bySoonest;
        };
    }

    private static String prettyCategory(String enumName) {
        if (enumName == null) return "Other";
        return switch (enumName.toUpperCase()) {
            case "CONCERT", "MUSIC" -> "Concerts";
            case "SPORTS"           -> "Sports";
            case "THEATER"          -> "Theatre";
            case "FESTIVAL"         -> "Festivals";
            case "COMEDY"           -> "Comedy";
            default                 -> "Other";
        };
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Event name in bold, with the line-up (if any) as a muted second line. */
    private static String nameCellHtml(EventRow ev) {
        String html = "<b>" + escape(ev.name) + "</b>";
        if (ev.artists != null && !ev.artists.isEmpty()) {
            String lineup = String.join(" · ", ev.artists.stream().map(BrowseEventsView::escape).toList());
            html += "<div style=\"color:var(--muted);font-size:12.5px;margin-top:2px;\">" + lineup + "</div>";
        }
        return html;
    }

    /** Rating shown without a trailing ".0" (e.g. 4.5 → "4.5", 4.0 → "4"). */
    private static String ratingText(double rating) {
        return rating == Math.floor(rating) ? String.valueOf((int) rating) : String.valueOf(rating);
    }
}
