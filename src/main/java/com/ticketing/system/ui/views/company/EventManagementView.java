package com.ticketing.system.ui.views.company;

import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.shared.dto.EventUpdateDTO;
import com.ticketing.system.shared.dto.LocationDTO;
import com.ticketing.system.shared.dto.ShowDateDTO;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.shared.domain.TextLimits;
import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.company.EventCancelConfirm;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBadge;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkCol;
import com.ticketing.system.ui.components.kit.LkFieldRules;
import com.ticketing.system.ui.components.kit.LkFormErrors;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkTextLimit;
import com.ticketing.system.ui.components.kit.LkTextRows;
import com.ticketing.system.ui.layouts.WorkspaceLayout;
import com.ticketing.system.ui.presenters.company.EventManagementPresenter;
import com.ticketing.system.ui.security.Capabilities;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.RequireCapability;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.CurrentCompanies;
import com.ticketing.system.ui.views.catalog.CatalogFilterSupport;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Focusable;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.shared.HasValidationProperties;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Route(value = "owner/events/:eventId", layout = WorkspaceLayout.class)
@PermitAll
@RequireCapability(Capability.EDIT_COMPANY_EVENTS)
public class EventManagementView extends LkPage implements BeforeEnterObserver, HasDynamicTitle {

    private static final String VENUE_TIME_HINT = "Local time at the venue";
    private static final String EDIT_TITLE = "Edit Event";
    private static final String CREATE_TITLE = "Create Event";
    private static final String TITLE_SUFFIX = " · TicketHub";
    private static final String EDIT_SUBTITLE = "Configure event details.";
    private static final String SAVE_LABEL = "Save Changes";
    private static final String EVENT_NOT_FOUND = "Event not found.";
    private static final String NO_ARTIST = "Please list at least one artist.";
    private static final String ENTER_TITLE = "Please enter a title.";
    private static final String CHOOSE_CATEGORY = "Please choose a category.";
    private static final String ENTER_COUNTRY = "Enter the country.";
    private static final String ENTER_CITY = "Enter the city.";
    private static final String SET_START = "Set the start time.";
    private static final String SET_END = "Set the end time.";
    private static final String SHOW_TIME_FORMAT = "Enter a valid date and time.";
    private static final String SHOW_TIME_INCOMPLETE = "Enter both a date and a time.";
    private static final String CHOOSE_TIME_ZONE = "Please choose the venue's time zone.";
    // An event's rating has the bounds the catalog filters search it by, so it shares their words too.
    private static final String SET_RATING =
        "Please set a rating (" + CatalogFilterSupport.RATING_MIN + "–" + CatalogFilterSupport.RATING_MAX + ").";

    private final TextField title = new TextField("Title");
    private final Select<String> category = new Select<>();
    private final TextField country = new TextField("Country");
    private final TextField city = new TextField("City");
    private final LkTextRows artists = new LkTextRows("Artists", "+ Add artist").placeholder("e.g. The Beatles");
    /** The venue's zone; the start and end pickers are wall-clock times in it (ADR-008). */
    private final ComboBox<ZoneId> timeZone = new ComboBox<>("Time zone");
    private final DateTimePicker start = new DateTimePicker("Starts");
    private final DateTimePicker end = new DateTimePicker("Ends");
    private final NumberField rating = new NumberField("Rating (0–5)");
    private final TextArea description = new TextArea("Description");

    /** Status badge lives in its own slot so the loader can fill it after the DTO arrives. */
    private final Div statusSlot = new Div();

    private final EventManagementPresenter presenter;
    private final LkFormErrors errors = new LkFormErrors();
    /** The primary action button — relabelled "Create Event" in create mode. */
    private LkBtn saveBtn;
    /** Linked editors / status / danger zone — hidden in create mode (they need a saved event). */
    private Component sideCol;
    private String eventId = null;   // "new"/null = create flow
    /** Whether the route names no saved event, so the page creates one; set on each entry. */
    private boolean createMode;
    /**
     * The id of the event being edited, parsed from the route; {@code null} in create mode and when the
     * route's id is no number, so no action reaches the service with an id that names no event.
     */
    private Integer editedEventId;
    /** The event's full schedule as loaded — kept so an edit to the first show preserves the rest. */
    private List<ShowDate> loadedShowDates = List.of();
    /** The event's name as loaded, for the cancel confirmation; the title field may hold an unsaved edit. */
    private String loadedEventName = "";

    public EventManagementView(EventManagementPresenter presenter) {
        this.presenter = presenter;
        title(EDIT_TITLE);
        subtitle(EDIT_SUBTITLE);
        saveBtn = new LkBtn(SAVE_LABEL).variant(LkBtn.Variant.primary)
            .onClick(e -> saveEvent());
        actions(
            new LkBtn("Discard").variant(LkBtn.Variant.tertiary)
                .onClick(e -> UI.getCurrent().navigate(CompanyEventListView.class)),
            saveBtn
        );
        add(buildSplit());
    }

    /**
     * The document title: "Create Event" on the create page, "Edit Event" otherwise, since one route serves
     * both and a fixed title would call the create page an edit (#188, WCAG 2.4.2).
     */
    @Override
    public String getPageTitle() {
        return (createMode ? CREATE_TITLE : EDIT_TITLE) + TITLE_SUFFIX;
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        eventId = event.getRouteParameters().get("eventId").orElse(null);
        createMode = eventId == null || "new".equals(eventId);
        editedEventId = createMode ? null : parseEventId(eventId).orElse(null);
        saveBtn.setEnabled(true);
        if (createMode) {
            title(CREATE_TITLE);
            subtitle("Create the event as a draft, then configure its venue and publish it.");
            saveBtn.label(CREATE_TITLE);
            artists.readOnly(false);
            markCreateRequirements(true);
            timeZone.setValue(presenter.defaultTimeZone());
            // Linked editors, status and the danger zone all act on a persisted event.
            sideCol.setVisible(false);
        } else {
            // After a create the router enters this same instance again for the new event, so the
            // edit page undoes what the create page set (#187, UI-A10).
            title(EDIT_TITLE);
            subtitle(EDIT_SUBTITLE);
            saveBtn.label(SAVE_LABEL);
            artists.readOnly(true);
            markCreateRequirements(false);
            if (editedEventId == null) {
                // The id is typed by the visitor: one that is no number names no event (#187, UI-A15).
                // Nothing is loaded, and the actions that need an event are not offered.
                Toasts.failure(EVENT_NOT_FOUND);
                saveBtn.setEnabled(false);
                sideCol.setVisible(false);
                return;
            }
            sideCol.setVisible(true);
            loadEvent(editedEventId);
        }
    }

    private static Optional<Integer> parseEventId(String routeValue) {
        try {
            return Optional.of(Integer.parseInt(routeValue));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private void loadEvent(int id) {
        switch (presenter.load(AuthSession.token(), id)) {
            case EventManagementPresenter.LoadOutcome.Success ok -> applyEvent(ok.event());
            case EventManagementPresenter.LoadOutcome.NotFound ignored ->
                Toasts.failure(EVENT_NOT_FOUND);
            case EventManagementPresenter.LoadOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case EventManagementPresenter.LoadOutcome.Failure fail ->
                Toasts.failure(Lk.withReason("Could not load the event", fail.reason()));
        }
    }

    /** Populates every control from the loaded event — no hardcoded values anywhere. */
    private void applyEvent(EventDetailDTO ev) {
        loadedEventName = ev.name() != null ? ev.name() : "";
        title.setValue(loadedEventName);
        category.setValue(ev.category() != null ? ev.category().name() : null);
        artists.setValues(ev.artistsNames());
        if (ev.location() != null) {
            country.setValue(ev.location().country() != null ? ev.location().country() : "");
            city.setValue(ev.location().city() != null ? ev.location().city() : "");
        }
        timeZone.setValue(ev.timeZone());
        loadedShowDates = ev.showDates() != null ? ev.showDates() : List.of();
        if (!loadedShowDates.isEmpty()) {
            start.setValue(loadedShowDates.get(0).getStartTime());
            end.setValue(loadedShowDates.get(0).getEndTime());
        }
        // Rating isn't part of EventUpdateDTO (set only at creation) — show it read-only here.
        rating.setValue(ev.rating());
        rating.setReadOnly(true);
        description.setValue(ev.description() != null ? ev.description() : "");
        renderStatus(ev.status());
    }

    private void renderStatus(EventStatus status) {
        statusSlot.removeAll();
        if (status == null) {
            return;
        }
        statusSlot.add(new LkBadge(status.name().replace('_', ' '), toneFor(status)).small());
    }

    private static LkBadge.Tone toneFor(EventStatus status) {
        return switch (status) {
            case ON_SALE -> LkBadge.Tone.success;
            case SCHEDULED, SOLD_OUT -> LkBadge.Tone.warn;
            case CANCELED -> LkBadge.Tone.error;
            case DRAFT, COMPLETED -> LkBadge.Tone.muted;
        };
    }

    private void saveEvent() {
        if ("new".equals(eventId) || eventId == null) {
            createEvent();
            return;
        }
        if (editedEventId == null) {
            Toasts.failure(EVENT_NOT_FOUND);
            return;
        }
        switch (presenter.save(AuthSession.token(),
                new EventUpdateDTO(
                    eventId,
                    blankToNull(title.getValue()),
                    blankToNull(description.getValue()),
                    category.getValue(),                 // null = leave category alone
                    buildLocation(),                     // null = leave location alone
                    timeZone.getValue(),                 // null = leave the time zone alone
                    buildShowDates()                     // null = leave schedule alone
                ))) {
            case EventManagementPresenter.SaveOutcome.Success ignored ->
                Toasts.success("Event details saved.");
            case EventManagementPresenter.SaveOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case EventManagementPresenter.SaveOutcome.Failure fail ->
                Toasts.failure(Lk.withReason("Could not save the event", fail.reason()));
        }
    }

    /** Create flow (the route names no saved event): checks the fields here, then hands off to the presenter. */
    private void createEvent() {
        errors.clear();
        String name = blankToNull(title.getValue());
        if (name == null) { refuse(title, ENTER_TITLE); return; }
        if (category.getValue() == null) { refuse(category, CHOOSE_CATEGORY); return; }

        String co = country.getValue() == null ? "" : country.getValue().trim();
        String ci = city.getValue() == null ? "" : city.getValue().trim();
        if (co.isBlank()) { errors.reject(country, ENTER_COUNTRY); }
        if (ci.isBlank()) { errors.reject(city, ENTER_CITY); }
        if (!errors.isEmpty()) { refuse("Please enter both a country and a city."); return; }

        if (start.getValue() == null) { errors.reject(start, SET_START); }
        if (end.getValue() == null) { errors.reject(end, SET_END); }
        if (!errors.isEmpty()) { refuse("Please set both a start and an end time."); return; }
        if (!end.getValue().isAfter(start.getValue())) {
            refuse(end, "The end time must be after the start time.");
            return;
        }
        // Whether the start is still ahead depends on the venue's zone, so the service judges it
        // against the injected clock and the presenter surfaces its reason.
        if (timeZone.getValue() == null) { refuse(timeZone, CHOOSE_TIME_ZONE); return; }

        List<String> artistList = artists.getValues();
        if (artistList.isEmpty()) {
            errors.reject(artists, NO_ARTIST);
            refuse(NO_ARTIST);
            return;
        }

        if (rating.getValue() == null) { refuse(rating, SET_RATING); return; }

        switch (presenter.create(AuthSession.token(), CurrentCompanies.currentCompanyId(),
                name, blankToNull(description.getValue()), category.getValue(),
                co, ci, start.getValue(), end.getValue(), timeZone.getValue(), artistList,
                rating.getValue())) {
            case EventManagementPresenter.CreateOutcome.Success ok -> {
                Toasts.success("Event created as a draft — configure the venue map and publish when ready.");
                UI.getCurrent().navigate("owner/events/" + ok.eventId());
            }
            case EventManagementPresenter.CreateOutcome.NoCompany ignored ->
                Toasts.failure("You can only create events for a company you own.");
            case EventManagementPresenter.CreateOutcome.ChooseCompany ignored ->
                Toasts.failure("Choose the company in the workspace's Company selector, "
                    + "or under My companies, then come back.");
            case EventManagementPresenter.CreateOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case EventManagementPresenter.CreateOutcome.InvalidInput bad ->
                Toasts.failure(bad.reason());
            case EventManagementPresenter.CreateOutcome.Failure fail ->
                Toasts.failure(Lk.withReason("Could not create the event", fail.reason()));
        }
    }

    // Create refuses these when empty; an edit save treats a blank one as "leave alone", so on the edit page
    // a required indicator would claim a rule the form doesn't enforce (#188, WCAG 3.3.2).
    private void markCreateRequirements(boolean create) {
        category.setRequiredIndicatorVisible(create);
        country.setRequiredIndicatorVisible(create);
        city.setRequiredIndicatorVisible(create);
        start.setRequiredIndicatorVisible(create);
        end.setRequiredIndicatorVisible(create);
    }

    // The toast stays, but the refused fields now carry the message too, and the first takes focus (#188).
    private void refuse(String toast) {
        Toasts.failure(toast);
        errors.focusFirst();
    }

    private <F extends Component & HasValidationProperties & Focusable<?>> void refuse(F field, String message) {
        errors.reject(field, message);
        refuse(message);
    }

    /** Location is country+city in the domain; both are required, so only send it when both are present. */
    private LocationDTO buildLocation() {
        String co = country.getValue() == null ? "" : country.getValue().trim();
        String ci = city.getValue() == null ? "" : city.getValue().trim();
        if (co.isBlank() || ci.isBlank()) {
            return null;
        }
        return new LocationDTO(co, ci);
    }

    /**
     * A non-null schedule replaces the whole list on save, so reconstruct it: the edited
     * first show plus any additional shows the event already had (preserved untouched).
     * Null when either picker is empty = "leave the schedule alone".
     */
    private List<ShowDateDTO> buildShowDates() {
        if (start.getValue() == null || end.getValue() == null) {
            return null;
        }
        List<ShowDateDTO> result = new ArrayList<>();
        result.add(new ShowDateDTO(start.getValue(), end.getValue()));
        for (int i = 1; i < loadedShowDates.size(); i++) {
            result.add(new ShowDateDTO(loadedShowDates.get(i).getStartTime(),
                                       loadedShowDates.get(i).getEndTime()));
        }
        return result;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private Component buildSplit() {
        Div split = new Div();
        split.addClassName("ow-edit-split");
        sideCol = buildSideCol();
        split.add(buildDetailsCard(), sideCol);
        return split;
    }

    private Component buildDetailsCard() {
        LkCard card = new LkCard("Event Details").pad(20).flush();

        // Each rule a field checks itself gets its words, or it turns red with nothing saying why (#189). Category,
        // country, city and the show times are required on the create page alone (markCreateRequirements).
        title.setRequired(true);
        LkFieldRules.sayWhenEmpty(title, ENTER_TITLE);
        title.setWidthFull();
        LkTextLimit.apply(title);
        artists.textLimit(TextLimits.MAX_TEXT_LENGTH);

        category.setLabel("Category");
        category.setItems(Arrays.stream(EventCategory.values()).map(Enum::name).toList());
        category.setI18n(new Select.SelectI18n().setRequiredErrorMessage(CHOOSE_CATEGORY));
        category.setWidthFull();

        LkFieldRules.sayWhenEmpty(country, ENTER_COUNTRY);
        country.setWidthFull();
        LkFieldRules.sayWhenEmpty(city, ENTER_CITY);
        city.setWidthFull();

        artists.setWidthFull();

        timeZone.setItems(ZoneId.getAvailableZoneIds().stream().sorted().map(ZoneId::of).toList());
        timeZone.setItemLabelGenerator(ZoneId::getId);
        timeZone.setRequired(true);
        timeZone.setI18n(new ComboBox.ComboBoxI18n().setRequiredErrorMessage(CHOOSE_TIME_ZONE));
        timeZone.setWidthFull();

        start.setHelperText(VENUE_TIME_HINT);
        start.setI18n(showTimeRules(SET_START));
        start.setWidthFull();
        end.setHelperText(VENUE_TIME_HINT);
        end.setI18n(showTimeRules(SET_END));
        end.setWidthFull();

        rating.setMin(CatalogFilterSupport.RATING_MIN);
        rating.setMax(CatalogFilterSupport.RATING_MAX);
        // No step constraint: accept any decimal (e.g. 4.1); only out-of-range [0,5] is flagged invalid.
        rating.setRequiredIndicatorVisible(true);
        rating.setI18n(new NumberField.NumberFieldI18n()
            .setRequiredErrorMessage(SET_RATING)
            .setBadInputErrorMessage(CatalogFilterSupport.RATING_NOT_A_NUMBER)
            .setMinErrorMessage(CatalogFilterSupport.RATING_OUT_OF_RANGE)
            .setMaxErrorMessage(CatalogFilterSupport.RATING_OUT_OF_RANGE));
        rating.setWidthFull();

        description.setMinHeight("120px");
        description.setWidthFull();
        LkTextLimit.apply(description);

        Div grid = new Div();
        grid.getStyle()
                .set("display", "grid")
                .set("grid-template-columns", "repeat(auto-fit, minmax(min(100%, 220px), 1fr))")
                .set("gap", "14px");
        grid.add(title, category, country, city, timeZone, start, end, rating);

        LkCol col = new LkCol().gap(14);
        col.add(grid, artists, description);
        card.add(col);
        return card;
    }

    private static DateTimePicker.DateTimePickerI18n showTimeRules(String whenEmpty) {
        return new DateTimePicker.DateTimePickerI18n()
            .setRequiredErrorMessage(whenEmpty)
            .setBadInputErrorMessage(SHOW_TIME_FORMAT)
            .setIncompleteInputErrorMessage(SHOW_TIME_INCOMPLETE);
    }

    private Component buildSideCol() {
        LkCol col = new LkCol().gap(14);
        col.add(buildLinkedEditorsCard(), buildStatusCard());
        // Danger zone (cancel event) is owner-only — managers can edit details
        // but cannot cancel. Drop the entire card for users without the cap.
        if (Capabilities.has(Capability.CANCEL_EVENT)) {
            col.add(buildDangerCard());
        }
        return col;
    }

    private Component buildLinkedEditorsCard() {
        LkCard card = new LkCard("Linked Editors").pad(14);
        LkCol col = new LkCol().gap(8);
        col.add(
                linkRow("ticket", "Venue map + zones", this::openVenueEditor),
                linkRow("policy", "Purchase policies", () -> UI.getCurrent().navigate(PurchasePolicyEditorView.class)),
                linkRow("chart", "Sales for this event", () -> UI.getCurrent().navigate(CompanySalesView.class)));
        card.add(col);
        return card;
    }

    private void openVenueEditor() {
        if (editedEventId != null) {
            UI.getCurrent().navigate("owner/venue/" + editedEventId);
        }
    }

    // A button, so a keyboard reaches it and a screen reader names it by its label (#188).
    private NativeButton linkRow(String iconName, String label, Runnable r) {
        NativeButton row = new NativeButton();
        row.addClassName("ow-link-row");
        row.add(new LkIcon(iconName, 17));
        Span lbl = new Span(label);
        lbl.getStyle().set("flex", "1");
        row.add(lbl, Lk.decoration("→"));
        row.getElement().addEventListener("click", e -> r.run());
        return row;
    }

    private Component buildStatusCard() {
        LkCard card = new LkCard("Status").pad(14);
        card.add(statusSlot);
        return card;
    }

    private Component buildDangerCard() {
        LkCard card = new LkCard("Danger Zone").pad(14).danger();
        Span warn = new Span("Cancelling refunds every ticket holder and notifies them.");
        warn.getStyle().set("font-size", "13px").set("display", "block").set("margin-bottom", "10px");
        card.add(warn);
        card.add(new LkBtn("Cancel This Event").variant(LkBtn.Variant.error).full()
                .icon(new LkIcon("warning", 16))
                .onClick(e -> confirmCancel()));
        return card;
    }

    private void confirmCancel() {
        if (editedEventId == null) {
            return;
        }
        EventCancelConfirm.open(loadedEventName, this::cancelEvent);
    }

    private void cancelEvent() {
        // A cancel commits the event's CANCELED state before its refunds run, so a failure after it may
        // leave the event cancelled: each failure reloads the event to show its actual state.
        int cancelledEventId = editedEventId;
        switch (presenter.cancel(AuthSession.token(), cancelledEventId)) {
            case EventManagementPresenter.CancelOutcome.Success ignored -> {
                Toasts.success("Event cancelled — all holders refunded.");
                UI.getCurrent().navigate(CompanyEventListView.class);
            }
            case EventManagementPresenter.CancelOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case EventManagementPresenter.CancelOutcome.RefundFailed refundFailed -> {
                Toasts.warnUntilDismissed(Lk.withReason(refundFailed.cancelAgainRetries()
                    ? "The event is cancelled, but some refunds failed; cancel it again to retry them"
                    : "The event is cancelled, but some refunds failed", refundFailed.reason()));
                loadEvent(cancelledEventId);
            }
            case EventManagementPresenter.CancelOutcome.Failure fail -> {
                Toasts.failure(Lk.withReason("Could not cancel the event", fail.reason()));
                loadEvent(cancelledEventId);
            }
        }
    }
}
