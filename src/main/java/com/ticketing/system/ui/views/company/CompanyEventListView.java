package com.ticketing.system.ui.views.company;

import com.ticketing.system.shared.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.company.EventCancelConfirm;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBanner;
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
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.presenters.company.CompanyEventListPresenter;
import com.ticketing.system.ui.security.Capabilities;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.RequireCapability;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.CurrentCompanies;
import com.ticketing.system.ui.support.DisplayTime;
import com.ticketing.system.ui.views.company.CompanySalesView;
import com.ticketing.system.ui.views.catalog.CatalogFilterSupport;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasAriaLabel;
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
import java.util.Locale;
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
    private static final DateTimeFormatter DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a zzz", Locale.US);

    private final CompanyEventListPresenter presenter;
    private final DisplayTime displayTime;
    private final LkCard eventsCard = new LkCard().pad(0);
    private final Div filterErrorSlot = new Div();
    /** How many events the filters leave, said to a screen reader after each reload (#188, WCAG 4.1.3). */
    private final Span resultCount = Lk.asStatus(new Span());

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

    public CompanyEventListView(CompanyEventListPresenter presenter, DisplayTime displayTime) {
        this.presenter = presenter;
        this.displayTime = displayTime;
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
        add(filterErrorSlot);
        // Heard, not shown: a filter rebuilds the list in place, and a sighted user sees its rows change.
        resultCount.addClassName(Lk.VISUALLY_HIDDEN);
        add(resultCount);
        add(eventsCard);
        Span hint = Lk.muted("Row actions → Edit metadata · Venue map · Policies · Sales · Status · Cancel.");
        hint.getStyle().set("font-size", "12.5px");
        add(hint);
        reload();
    }

    private void reload() {
        eventsCard.removeAll();
        // Only a loaded list has a count; the card says why the others have none.
        resultCount.setText("");
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
                resultCount.setText(events.size() + (events.size() == 1 ? " event" : " events"));
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
        customFromPicker.setPlaceholder(CatalogFilterSupport.CUSTOM_FROM);
        customFromPicker.setAriaLabel(CatalogFilterSupport.CUSTOM_FROM);
        customFromPicker.addValueChangeListener(e -> { customToPicker.setMin(e.getValue()); reloadCustomIfValid(); });
        customToPicker = new DatePicker();
        customToPicker.setPlaceholder(CatalogFilterSupport.CUSTOM_TO);
        customToPicker.setAriaLabel(CatalogFilterSupport.CUSTOM_TO);
        customToPicker.addValueChangeListener(e -> { customFromPicker.setMax(e.getValue()); reloadCustomIfValid(); });
        customRangeRow = new Div();
        customRangeRow.getStyle().set("display", "flex").set("gap", "6px");
        customRangeRow.add(customFromPicker, customToPicker);
        customRangeRow.setVisible(false);

        priceMin = numberRangeField(CatalogFilterSupport.BOUND_MIN, v -> { filterPriceMin = v; reload(); });
        priceMax = numberRangeField(CatalogFilterSupport.BOUND_MAX, v -> { filterPriceMax = v; reload(); });

        eventRatingMin = ratingField(CatalogFilterSupport.BOUND_MIN, v -> { filterMinEventRating = v; reload(); });
        eventRatingMax = ratingField(CatalogFilterSupport.BOUND_MAX, v -> { filterMaxEventRating = v; reload(); });

        countrySelect = new LkSelect(CatalogFilterSupport.COUNTRY_ALL,
                CatalogFilterSupport.countryOptions(filterOptions(
                        presenter.countries(AuthSession.token(), CurrentCompanies.currentCompanyId()),
                        "Could not load the countries"))).label("Country");
        countrySelect.onChange(v -> {
            if (Objects.equals(filterCountry, v)) return;
            filterCountry = v;
            filterCity = CatalogFilterSupport.CITY_ALL;
            filterErrorSlot.removeAll();
            citySelect.setOptions(CatalogFilterSupport.COUNTRY_ALL.equals(v)
                    ? List.of(CatalogFilterSupport.CITY_ALL)
                    : CatalogFilterSupport.cityOptions(filterOptions(
                            presenter.cities(AuthSession.token(), CurrentCompanies.currentCompanyId(), v),
                            "Could not load the cities")));
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
            rangeGroup("Price range", priceMin, priceMax),
            rangeGroup("Event rating", eventRatingMin, eventRatingMax),
            countrySelect, citySelect, statusSelect,
            clear);
        return bar;
    }

    // A failed options read leaves only the "All" choice, and says why under the filter bar.
    private List<String> filterOptions(ListOutcome<String> outcome, String failureContext) {
        return switch (outcome) {
            case ListOutcome.Loaded<String> loaded -> loaded.items();
            case ListOutcome.Failure<String> failure -> {
                // An alert: a filter choice inserts it, so a screen reader must announce it.
                filterErrorSlot.add(Lk.asAlert(new LkBanner(LkBanner.Tone.error, new LkIcon("warning", 16),
                        Lk.withReason(failureContext, failure.reason()))));
                yield List.of();
            }
        };
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
            CatalogFilterSupport.DateWindow dw = CatalogFilterSupport.dateWindow(filterDateRange, displayTime.today());
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

    // The span shows the label but is tied to nothing, so the field carries the same text as its name (#188).
    private static <T extends Component & HasAriaLabel> Component labelled(String labelText, T field) {
        field.setAriaLabel(labelText);
        return labelBlock(labelText, field);
    }

    // A named group, so a screen reader hears each bound as "Price range, Min" rather than a bare "Min" (#188).
    private static <T extends Component & HasAriaLabel> Component rangeGroup(String labelText, T min, T max) {
        min.setAriaLabel(CatalogFilterSupport.BOUND_MIN);
        max.setAriaLabel(CatalogFilterSupport.BOUND_MAX);
        return Lk.asGroup(labelBlock(labelText, min, max), labelText);
    }

    private static Div labelBlock(String labelText, Component... fields) {
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
            ? ev.showDates().get(0).getStartTime().atZone(ev.timeZone()).format(DATE_TIME_FMT) : "—";
        row.put("date", date);

        String venue = ev.location() != null ? ev.location().toString() : "—";
        row.put("venue", venue);

        // Cheapest ticket price (the same value the catalog's price-range filter matches on); "—"
        // for events with no venue/zones yet (e.g. drafts). Format with cents so it matches the
        // actual ticket price (e.g. $49.99, not a truncated $49).
        row.put("from", ev.minPrice() > 0 ? String.format("$%,.2f", ev.minPrice()) : "—");

        // Read as "Rating 4.5 of 5", not "star 4.5" (#188).
        row.put("rating", ev.rating() == null ? "—" : Lk.rating(ratingText(ev.rating())));

        LkStatusDot.Tone tone = ev.status() == EventStatus.ON_SALE ? LkStatusDot.Tone.ok
            : ev.status() == EventStatus.DRAFT ? LkStatusDot.Tone.muted
            : LkStatusDot.Tone.warn;
        row.put("status", new LkStatusDot(tone, ev.status().name()));

        // Each action is gated by the capability it actually needs, so a member only sees the
        // actions they can perform — e.g. a venue-only manager (CONFIGURE_VENUE) sees just "Venue
        // map", a sales manager just "Sales", an owner the full set.
        LkRow actions = new LkRow().gap(4).noWrap();
        if (Capabilities.has(Capability.EDIT_COMPANY_EVENTS)) {
            actions.add(iconBtn("edit", "Edit metadata", ev.name(), () -> UI.getCurrent().navigate("owner/events/" + ev.eventId())));
        }
        if (Capabilities.has(Capability.MANAGE_VENUE_MAPS)) {
            actions.add(iconBtn("map", "Venue map", ev.name(), () -> UI.getCurrent().navigate("owner/venue/" + ev.eventId())));
        }
        if (Capabilities.has(Capability.EDIT_PURCHASE_POLICIES)) {
            // Deep-link to THIS event's purchase policy (route owner/policies/:companyId?/:eventId?).
            actions.add(iconBtn("policy", "Policies", ev.name(),
                    () -> UI.getCurrent().navigate("owner/policies/" + ev.companyId() + "/" + ev.eventId())));
        }
        if (Capabilities.has(Capability.VIEW_COMPANY_SALES)) {
            actions.add(iconBtn("chart", "Sales", ev.name(), () -> UI.getCurrent().navigate(CompanySalesView.class)));
        }
        if (ev.status() == EventStatus.CANCELED) {
            // A canceled event has no further status transitions, so this list only offers to
            // remove it. Cancelling it again, which resumes refunds a failure interrupted (#20),
            // is done from the event's management page.
            if (Capabilities.has(Capability.EDIT_COMPANY_EVENTS)) {
                actions.add(iconBtn("trash", "Remove event", ev.name(), () -> openDeleteDialog(ev)));
            }
        } else {
            if (Capabilities.has(Capability.EDIT_COMPANY_EVENTS)) {
                actions.add(iconBtn("gear", "Change status", ev.name(), () -> openStatusDialog(ev)));
            }
            if (Capabilities.has(Capability.CANCEL_EVENT)) {
                actions.add(iconBtn("warning", "Cancel event", ev.name(), () -> openCancelDialog(ev)));
            }
        }
        row.put("act", actions);
        grid.row(row);
    }

    private void openCancelDialog(EventDetailDTO ev) {
        EventCancelConfirm.open(ev.name(), () -> {
            int eventId = Integer.parseInt(ev.eventId());
            // A cancel commits the event's CANCELED state before its refunds run, so a failure after it
            // may leave the event cancelled: each failure reloads the list to show its actual state.
            switch (presenter.cancelEvent(AuthSession.token(), eventId)) {
                case CompanyEventListPresenter.CancelOutcome.Success ignored -> {
                    Toasts.success("Event canceled and refunds issued.");
                    reload();
                }
                case CompanyEventListPresenter.CancelOutcome.NotAuthenticated ignored ->
                    Toasts.warn("Your session has expired — please sign in again.");
                case CompanyEventListPresenter.CancelOutcome.RefundFailed refundFailed -> {
                    Toasts.warnUntilDismissed(Lk.withReason(refundFailed.cancelAgainRetries()
                        ? "The event is cancelled, but some refunds failed; cancel it again from its page to retry them"
                        : "The event is cancelled, but some refunds failed", refundFailed.reason()));
                    reload();
                }
                case CompanyEventListPresenter.CancelOutcome.Failure fail -> {
                    Toasts.failure(Lk.withReason("Could not cancel the event", fail.reason()));
                    reload();
                }
            }
        });
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
        // A visible label names the select; the placeholder alone left a screen reader nothing to say (#188).
        select.setLabel("New status");
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

    // Named for its event too, since every row repeats the same buttons (#188).
    private static LkIconBtn iconBtn(String iconName, String tooltip, String eventName, Runnable r) {
        LkIconBtn b = new LkIconBtn(new LkIcon(iconName, 15), tooltip).forRow(eventName);
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
