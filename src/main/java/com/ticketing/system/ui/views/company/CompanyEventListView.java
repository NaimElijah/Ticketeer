package com.ticketing.system.ui.views.company;

import com.ticketing.system.Core.Application.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.Core.Application.dto.EventDetailDTO;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkDateRangeField;
import com.ticketing.system.ui.components.kit.LkGrid;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkIconBtn;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkRow;
import com.ticketing.system.ui.components.kit.LkSelect;
import com.ticketing.system.ui.components.kit.LkStatusDot;
import com.ticketing.system.ui.layouts.WorkspaceLayout;
import com.ticketing.system.ui.presenters.company.CompanyEventListPresenter;
import com.ticketing.system.ui.security.Capabilities;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.RequireCapability;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.CurrentCompanies;
import com.ticketing.system.ui.views.company.CompanySalesView;
import com.ticketing.system.ui.views.catalog.CatalogFilterSupport;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

@Route(value = "owner/events", layout = WorkspaceLayout.class)
@PageTitle("My Events · TicketHub")
@PermitAll
@RequireCapability(Capability.VIEW_COMPANY_EVENTS)
public class CompanyEventListView extends LkPage {

    private static final String STATUS_ALL = "All statuses";
    private static final List<String> STATUS_OPTIONS = List.of(
            STATUS_ALL, "Draft", "Scheduled", "On sale", "Sold out", "Cancelled", "Completed");

    /** Human-readable start date + time, e.g. "Jul 19, 2026 · 5:41 PM" (no ISO "T"/seconds gibberish). */
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a");

    private final CompanyEventListPresenter presenter;
    private final LkCard eventsCard = new LkCard().pad(0);

    // ---- filter state (mirrors /browse, minus company-rating; status is owner-only & client-side) ----
    private String filterCategory  = CatalogFilterSupport.CAT_ALL;
    private String filterCountry   = CatalogFilterSupport.COUNTRY_ALL;
    private String filterCity      = CatalogFilterSupport.CITY_ALL;
    private String filterArtist    = "";
    private String filterEventName = "";
    private String filterKeywords  = "";
    private String filterDateRange = CatalogFilterSupport.DATE_ANY;
    private Integer filterPriceMin = null;
    private Integer filterPriceMax = null;
    private Double filterMinEventRating = null;
    private Double filterMaxEventRating = null;
    private String filterStatus = STATUS_ALL;

    // ---- ui references for reset ----
    private LkSelect categorySelect;
    private LkSelect countrySelect;
    private LkSelect citySelect;
    private LkSelect statusSelect;
    private LkDateRangeField dateRangeField;
    private DatePicker customFromPicker;
    private DatePicker customToPicker;
    private Div customRangeRow;
    private TextField eventNameField;
    private TextField keywordsField;
    private TextField artistField;
    private IntegerField priceMin;
    private IntegerField priceMax;
    private NumberField eventRatingMin;
    private NumberField eventRatingMax;

    public CompanyEventListView(CompanyEventListPresenter presenter) {
        this.presenter = presenter;
        title("My Events");
        subtitle("All events under the selected company.");
        // "New Event" is an event-management action — hidden for a member who can't edit events
        // (e.g. a venue-only manager), so the page is browse + per-event venue/sales for them.
        if (Capabilities.has(Capability.EDIT_COMPANY_EVENTS)) {
            actions(new LkBtn("New Event").variant(LkBtn.Variant.primary)
                .icon(new LkIcon("plus", 15))
                .onClick(e -> UI.getCurrent().navigate("owner/events/new")));
        }
        add(buildFilters());
        add(eventsCard);
        Span hint = Lk.muted("Row actions → Edit metadata · Venue map · Policies · Sales · Status · Cancel.");
        hint.getStyle().set("font-size", "12.5px");
        add(hint);
        reload();
    }

    private void reload() {
        eventsCard.removeAll();
        LkGrid grid = new LkGrid()
            .col("Event",   "name")
            .col("Date",    "date")
            .col("Venue",   "venue")
            .col("From",    "from", LkGrid.Align.RIGHT)
            .col("Rating",  "rating", LkGrid.Align.RIGHT)
            .col("Status",  "status")
            .col("Actions", "act", LkGrid.Align.RIGHT);
        switch (presenter.load(AuthSession.token(), CurrentCompanies.currentCompanyId(), currentFilters())) {
            case CompanyEventListPresenter.Outcome.Success ok -> {
                // Status isn't a CatalogSearchFiltersDTO field — apply it client-side on the result.
                EventStatus wanted = statusValue(filterStatus);
                List<EventDetailDTO> events = ok.events().stream()
                    .filter(ev -> wanted == null || ev.status() == wanted)
                    .toList();
                if (events.isEmpty()) {
                    eventsCard.add(Lk.muted("No events match your filters."));
                } else {
                    for (EventDetailDTO ev : events) {
                        addEventRow(grid, ev);
                    }
                    grid.build();
                    eventsCard.add(grid);
                }
            }
            case CompanyEventListPresenter.Outcome.NoCompany ignored ->
                eventsCard.add(Lk.muted("You're not part of a company workspace yet."));
            case CompanyEventListPresenter.Outcome.NotAuthenticated ignored ->
                eventsCard.add(Lk.muted("Your session has expired — please sign in again."));
            case CompanyEventListPresenter.Outcome.Failure fail ->
                eventsCard.add(Lk.muted("Could not load events: " + fail.reason()));
        }
    }

    // ---- filter bar (same controls as /browse minus company-rating, plus an owner-only Status) ----

    private Component buildFilters() {
        categorySelect = new LkSelect(CatalogFilterSupport.CAT_ALL, CatalogFilterSupport.CATEGORIES).label("Category");
        categorySelect.onChange(v -> { if (!Objects.equals(filterCategory, v)) { filterCategory = v; reload(); } });

        eventNameField = textFilter("e.g. Othello", v -> { filterEventName = v; reload(); });
        keywordsField  = textFilter("Search name or artist", v -> { filterKeywords = v; reload(); });
        artistField    = textFilter("e.g. Taylor Swift", v -> { filterArtist = v; reload(); });

        dateRangeField = new LkDateRangeField().label("Date range");
        dateRangeField.onChange(v -> {
            if (Objects.equals(filterDateRange, v)) return;
            filterDateRange = v;
            customRangeRow.setVisible("Custom range".equals(v));
            if ("Custom range".equals(v)) reloadCustomIfValid();
            else reload();
        });

        customFromPicker = new DatePicker();
        customFromPicker.setPlaceholder("Start date");
        customFromPicker.addValueChangeListener(e -> { customToPicker.setMin(e.getValue()); reloadCustomIfValid(); });
        customToPicker = new DatePicker();
        customToPicker.setPlaceholder("End date");
        customToPicker.addValueChangeListener(e -> { customFromPicker.setMax(e.getValue()); reloadCustomIfValid(); });
        customRangeRow = new Div();
        customRangeRow.getStyle().set("display", "flex").set("gap", "6px");
        customRangeRow.add(customFromPicker, customToPicker);
        customRangeRow.setVisible(false);

        priceMin = numberRangeField("Min", v -> { filterPriceMin = v; reload(); });
        priceMax = numberRangeField("Max", v -> { filterPriceMax = v; reload(); });

        eventRatingMin = ratingField("Min", v -> { filterMinEventRating = v; reload(); });
        eventRatingMax = ratingField("Max", v -> { filterMaxEventRating = v; reload(); });

        countrySelect = new LkSelect(CatalogFilterSupport.COUNTRY_ALL,
                CatalogFilterSupport.countryOptions(
                        presenter.countries(AuthSession.token(), CurrentCompanies.currentCompanyId()))).label("Country");
        countrySelect.onChange(v -> {
            if (Objects.equals(filterCountry, v)) return;
            filterCountry = v;
            filterCity = CatalogFilterSupport.CITY_ALL;
            citySelect.setOptions(CatalogFilterSupport.COUNTRY_ALL.equals(v)
                    ? List.of(CatalogFilterSupport.CITY_ALL)
                    : CatalogFilterSupport.cityOptions(
                            presenter.cities(AuthSession.token(), CurrentCompanies.currentCompanyId(), v)));
            citySelect.enabled(!CatalogFilterSupport.COUNTRY_ALL.equals(v));
            reload();
        });
        citySelect = new LkSelect(CatalogFilterSupport.CITY_ALL, List.of(CatalogFilterSupport.CITY_ALL)).label("City");
        citySelect.enabled(false);
        citySelect.onChange(v -> { if (!Objects.equals(filterCity, v)) { filterCity = v; reload(); } });

        statusSelect = new LkSelect(STATUS_ALL, STATUS_OPTIONS).label("Status");
        statusSelect.onChange(v -> { if (!Objects.equals(filterStatus, v)) { filterStatus = v; reload(); } });

        LkBtn clear = new LkBtn("Clear").variant(LkBtn.Variant.tertiary).onClick(e -> clearFilters());

        Div bar = new Div();
        bar.getStyle()
                .set("display", "flex").set("flex-wrap", "wrap")
                .set("gap", "12px").set("align-items", "flex-end").set("margin-bottom", "14px");
        bar.add(
            categorySelect,
            labelled("Event name", eventNameField),
            labelled("Keywords", keywordsField),
            labelled("Artist", artistField),
            dateRangeField, customRangeRow,
            labelled("Price range", priceMin, priceMax),
            labelled("Event rating", eventRatingMin, eventRatingMax),
            countrySelect, citySelect, statusSelect,
            clear);
        return bar;
    }

    private void clearFilters() {
        filterCategory = CatalogFilterSupport.CAT_ALL;
        filterCountry  = CatalogFilterSupport.COUNTRY_ALL;
        filterCity     = CatalogFilterSupport.CITY_ALL;
        filterArtist = "";
        filterEventName = "";
        filterKeywords = "";
        filterDateRange = CatalogFilterSupport.DATE_ANY;
        filterPriceMin = null;
        filterPriceMax = null;
        filterMinEventRating = null;
        filterMaxEventRating = null;
        filterStatus = STATUS_ALL;

        categorySelect.setValue(CatalogFilterSupport.CAT_ALL);
        countrySelect.setValue(CatalogFilterSupport.COUNTRY_ALL);
        citySelect.setOptions(List.of(CatalogFilterSupport.CITY_ALL));
        citySelect.enabled(false);
        statusSelect.setValue(STATUS_ALL);
        eventNameField.clear();
        keywordsField.clear();
        artistField.clear();
        dateRangeField.setValue(CatalogFilterSupport.DATE_ANY);
        customRangeRow.setVisible(false);
        customFromPicker.clear();
        customToPicker.clear();
        priceMin.clear();
        priceMax.clear();
        eventRatingMin.clear();
        eventRatingMax.clear();
        reload();
    }

    /** Re-query for the custom-range flow only when the picker values aren't inverted. */
    private void reloadCustomIfValid() {
        if (!"Custom range".equals(filterDateRange)) return;
        if (CatalogFilterSupport.isValidCustomRange(customFromPicker.getValue(), customToPicker.getValue()))
            reload();
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
                CatalogFilterSupport.locationFilter(filterCity, filterCountry),
                filterMinEventRating, filterMaxEventRating,
                null, null);
    }

    private static EventStatus statusValue(String label) {
        return switch (label) {
            case "Draft"     -> EventStatus.DRAFT;
            case "Scheduled" -> EventStatus.SCHEDULED;
            case "On sale"   -> EventStatus.ON_SALE;
            case "Sold out"  -> EventStatus.SOLD_OUT;
            case "Cancelled" -> EventStatus.CANCELED;
            case "Completed" -> EventStatus.COMPLETED;
            default          -> null; // All statuses
        };
    }

    private TextField textFilter(String placeholder, Consumer<String> onChange) {
        TextField f = new TextField();
        f.setPlaceholder(placeholder);
        f.setValueChangeMode(ValueChangeMode.LAZY);
        f.setWidth("180px");
        f.addValueChangeListener(e -> onChange.accept(e.getValue() == null ? "" : e.getValue()));
        return f;
    }

    private IntegerField numberRangeField(String placeholder, Consumer<Integer> onChange) {
        IntegerField f = new IntegerField();
        f.setPlaceholder(placeholder);
        f.setMin(0);
        f.setStep(10);
        f.setPrefixComponent(new Span("$"));
        f.setValueChangeMode(ValueChangeMode.LAZY);
        f.setWidth("100px");
        f.addValueChangeListener(e -> onChange.accept(e.getValue()));
        return f;
    }

    private NumberField ratingField(String placeholder, Consumer<Double> onChange) {
        NumberField f = new NumberField();
        f.setPlaceholder(placeholder);
        f.setMin(0);
        f.setMax(5);
        f.setStep(0.5);
        f.setValueChangeMode(ValueChangeMode.LAZY);
        f.setWidth("90px");
        f.addValueChangeListener(e -> onChange.accept(e.getValue()));
        return f;
    }

    private static Component labelled(String labelText, Component... fields) {
        Div wrap = new Div();
        Span label = new Span(labelText);
        label.addClassName("lk-label");
        label.getStyle().set("display", "block").set("margin-bottom", "6px").set("font-size", "12.5px");
        LkRow row = new LkRow().gap(6);
        row.add(fields);
        wrap.add(label, row);
        return wrap;
    }

    private void addEventRow(LkGrid grid, EventDetailDTO ev) {
        Map<String, Object> row = new LinkedHashMap<>();
        Span name = new Span();
        name.getElement().setProperty("innerHTML", nameCellHtml(ev));
        row.put("name", name);

        String date = ev.showDates() != null && !ev.showDates().isEmpty()
            ? ev.showDates().get(0).getStartTime().format(DATE_TIME_FMT) : "—";
        row.put("date", date);

        String venue = ev.location() != null ? ev.location().toString() : "—";
        row.put("venue", venue);

        // Cheapest ticket price (the same value the catalog's price-range filter matches on); "—"
        // for events with no venue/zones yet (e.g. drafts). Format with cents so it matches the
        // actual ticket price (e.g. $49.99, not a truncated $49).
        row.put("from", ev.minPrice() > 0 ? String.format("$%,.2f", ev.minPrice()) : "—");

        row.put("rating", ev.rating() == null ? "—" : "★ " + ratingText(ev.rating()));

        LkStatusDot.Tone tone = ev.status() == EventStatus.ON_SALE ? LkStatusDot.Tone.ok
            : ev.status() == EventStatus.DRAFT ? LkStatusDot.Tone.muted
            : LkStatusDot.Tone.warn;
        row.put("status", new LkStatusDot(tone, ev.status().name()));

        // Each action is gated by the capability it actually needs, so a member only sees the
        // actions they can perform — e.g. a venue-only manager (CONFIGURE_VENUE) sees just "Venue
        // map", a sales manager just "Sales", an owner the full set.
        LkRow actions = new LkRow().gap(4).noWrap();
        if (Capabilities.has(Capability.EDIT_COMPANY_EVENTS)) {
            actions.add(iconBtn("edit", "Edit metadata", () -> UI.getCurrent().navigate("owner/events/" + ev.eventId())));
        }
        if (Capabilities.has(Capability.MANAGE_VENUE_MAPS)) {
            actions.add(iconBtn("map", "Venue map", () -> UI.getCurrent().navigate("owner/venue/" + ev.eventId())));
        }
        if (Capabilities.has(Capability.EDIT_PURCHASE_POLICIES)) {
            // Deep-link to THIS event's purchase policy (route owner/policies/:companyId?/:eventId?).
            actions.add(iconBtn("policy", "Policies",
                    () -> UI.getCurrent().navigate("owner/policies/" + ev.companyId() + "/" + ev.eventId())));
        }
        if (Capabilities.has(Capability.VIEW_COMPANY_SALES)) {
            actions.add(iconBtn("chart", "Sales", () -> UI.getCurrent().navigate(CompanySalesView.class)));
        }
        if (ev.status() == EventStatus.CANCELED) {
            // A canceled event has no further transitions and can't be canceled again —
            // the only action is to permanently remove it.
            if (Capabilities.has(Capability.EDIT_COMPANY_EVENTS)) {
                actions.add(iconBtn("trash", "Remove event", () -> openDeleteDialog(ev)));
            }
        } else {
            if (Capabilities.has(Capability.EDIT_COMPANY_EVENTS)) {
                actions.add(iconBtn("gear", "Change status", () -> openStatusDialog(ev)));
            }
            if (Capabilities.has(Capability.CANCEL_EVENT)) {
                actions.add(iconBtn("warning", "Cancel event", () -> openCancelDialog(ev)));
            }
        }
        row.put("act", actions);
        grid.row(row);
    }

    private void openCancelDialog(EventDetailDTO ev) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Cancel \"" + ev.name() + "\"?");
        dialog.setText("This will refund all ticket holders and permanently cancel the event. This cannot be undone.");
        dialog.setCancelable(true);
        dialog.setCancelText("Keep event");
        dialog.setConfirmText("Cancel event");
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(ignored -> {
            int eventId = Integer.parseInt(ev.eventId());
            switch (presenter.cancelEvent(AuthSession.token(), eventId)) {
                case CompanyEventListPresenter.ActionOutcome.Success ignored2 -> {
                    Toasts.success("Event canceled and refunds issued.");
                    reload();
                }
                case CompanyEventListPresenter.ActionOutcome.NotAuthenticated ignored2 ->
                    Toasts.warn("Your session has expired — please sign in again.");
                case CompanyEventListPresenter.ActionOutcome.Failure fail ->
                    Toasts.failure("Could not cancel the event — please try again.");
            }
        });
        dialog.open();
    }

    private void openDeleteDialog(EventDetailDTO ev) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Remove \"" + ev.name() + "\"?");
        dialog.setText("The event record will be permanently deleted. This cannot be undone.");
        dialog.setCancelable(true);
        dialog.setCancelText("Keep");
        dialog.setConfirmText("Remove");
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(ignored -> {
            switch (presenter.deleteEvent(AuthSession.token(), Integer.parseInt(ev.eventId()))) {
                case CompanyEventListPresenter.ActionOutcome.Success ignored2 -> {
                    Toasts.success("Event removed.");
                    reload();
                }
                case CompanyEventListPresenter.ActionOutcome.NotAuthenticated ignored2 ->
                    Toasts.warn("Your session has expired — please sign in again.");
                case CompanyEventListPresenter.ActionOutcome.Failure fail ->
                    Toasts.failure(actionFailureMessage(fail.reason(), "Could not remove the event — please try again."));
            }
        });
        dialog.open();
    }

    private void openStatusDialog(EventDetailDTO ev) {
        List<EventStatus> allowed = allowedTransitions(ev.status());
        if (allowed.isEmpty()) {
            Toasts.warn("No further status changes are available for this event.");
            return;
        }

        Select<EventStatus> select = new Select<>();
        select.setItems(allowed);
        select.setItemLabelGenerator(s -> switch (s) {
            case SCHEDULED -> "Mark as Scheduled";
            case ON_SALE -> "Publish (On Sale)";
            default -> s.name();
        });
        select.setPlaceholder("Select new status…");

        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Change status — " + ev.name());
        dialog.add(select);
        dialog.setCancelable(true);
        dialog.setCancelText("Cancel");
        dialog.setConfirmText("Apply");
        dialog.addConfirmListener(ignored -> {
            EventStatus target = select.getValue();
            if (target == null) {
                Toasts.warn("Please select a status.");
                return;
            }
            switch (presenter.changeEventStatus(AuthSession.token(), Integer.parseInt(ev.eventId()), target)) {
                case CompanyEventListPresenter.ActionOutcome.Success ignored2 -> {
                    Toasts.success(statusChangeSuccessMessage(target));
                    reload();
                }
                case CompanyEventListPresenter.ActionOutcome.NotAuthenticated ignored2 ->
                    Toasts.warn("Your session has expired — please sign in again.");
                case CompanyEventListPresenter.ActionOutcome.Failure fail ->
                    Toasts.failure(actionFailureMessage(fail.reason(), "Could not change the event status — please try again."));
            }
        });
        dialog.open();
    }

    private static List<EventStatus> allowedTransitions(EventStatus current) {
        return switch (current) {
            case DRAFT -> List.of(EventStatus.SCHEDULED);
            case SCHEDULED -> List.of(EventStatus.ON_SALE);
            default -> List.of();
        };
    }

    /**
     * Surface the real reason the service refused an action (e.g. "Can't delete an event with sales
     * history", or "Event cannot be scheduled without a venue map and at least one inventory zone")
     * instead of a generic toast — so the owner understands why. Falls back to {@code fallback} only
     * when the service gave no message.
     */
    private static String actionFailureMessage(String reason, String fallback) {
        return (reason == null || reason.isBlank()) ? fallback : reason;
    }

    /** Informative, status-specific confirmation for a successful status change. */
    private static String statusChangeSuccessMessage(EventStatus target) {
        return switch (target) {
            case SCHEDULED -> "Event scheduled — you can publish it (put it On Sale) when ready.";
            case ON_SALE   -> "Event is now On Sale — tickets are available to buyers.";
            default        -> "Event status updated.";
        };
    }

    private static LkIconBtn iconBtn(String iconName, String tooltip, Runnable r) {
        LkIconBtn b = new LkIconBtn(new LkIcon(iconName, 15), tooltip);
        b.addClickListener(e -> r.run());
        return b;
    }

    /** Event name in bold, with the line-up (if any) as a muted second line — matches /browse. */
    private static String nameCellHtml(EventDetailDTO ev) {
        String html = "<b>" + escape(ev.name()) + "</b>";
        if (ev.artistsNames() != null && !ev.artistsNames().isEmpty()) {
            String lineup = String.join(" · ", ev.artistsNames().stream().map(CompanyEventListView::escape).toList());
            html += "<div style=\"color:var(--muted);font-size:12.5px;margin-top:2px;\">" + lineup + "</div>";
        }
        return html;
    }

    /** Rating shown without a trailing ".0" (e.g. 4.5 → "4.5", 4.0 → "4"). */
    private static String ratingText(double rating) {
        return rating == Math.floor(rating) ? String.valueOf((int) rating) : String.valueOf(rating);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
