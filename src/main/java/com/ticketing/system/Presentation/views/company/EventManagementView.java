package com.ticketing.system.Presentation.views.company;

import com.ticketing.system.Core.Application.dto.EventDetailDTO;
import com.ticketing.system.Core.Application.dto.EventUpdateDTO;
import com.ticketing.system.Core.Application.dto.LocationDTO;
import com.ticketing.system.Core.Application.dto.ShowDateDTO;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.Presentation.components.Toasts;
import com.ticketing.system.Presentation.components.kit.LkBadge;
import com.ticketing.system.Presentation.components.kit.LkBtn;
import com.ticketing.system.Presentation.components.kit.LkCard;
import com.ticketing.system.Presentation.components.kit.LkCol;
import com.ticketing.system.Presentation.components.kit.LkIcon;
import com.ticketing.system.Presentation.components.kit.LkPage;
import com.ticketing.system.Presentation.components.kit.LkTextRows;
import com.ticketing.system.Presentation.layouts.WorkspaceLayout;
import com.ticketing.system.Presentation.presenters.company.EventManagementPresenter;
import com.ticketing.system.Presentation.security.Capabilities;
import com.ticketing.system.Presentation.security.Capability;
import com.ticketing.system.Presentation.security.RequireCapability;
import com.ticketing.system.Presentation.session.AuthSession;
import com.ticketing.system.Presentation.session.CurrentCompanies;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Route(value = "owner/events/:eventId", layout = WorkspaceLayout.class)
@PageTitle("Edit Event · TicketHub")
@PermitAll
@RequireCapability(Capability.EDIT_COMPANY_EVENTS)
public class EventManagementView extends LkPage implements BeforeEnterObserver {

    private final TextField title = new TextField("Title");
    private final Select<String> category = new Select<>();
    private final TextField country = new TextField("Country");
    private final TextField city = new TextField("City");
    private final LkTextRows artists = new LkTextRows("Artists", "+ Add artist").placeholder("e.g. The Beatles");
    private final DateTimePicker start = new DateTimePicker("Starts");
    private final DateTimePicker end = new DateTimePicker("Ends");
    private final NumberField rating = new NumberField("Rating (0–5)");
    private final TextArea description = new TextArea("Description");

    /** Status badge lives in its own slot so the loader can fill it after the DTO arrives. */
    private final Div statusSlot = new Div();

    private final EventManagementPresenter presenter;
    /** The primary action button — relabelled "Create Event" in create mode. */
    private LkBtn saveBtn;
    /** Linked editors / status / danger zone — hidden in create mode (they need a saved event). */
    private Component sideCol;
    private String eventId = null;   // "new"/null = create flow
    /** The event's full schedule as loaded — kept so an edit to the first show preserves the rest. */
    private List<ShowDate> loadedShowDates = List.of();

    public EventManagementView(EventManagementPresenter presenter) {
        this.presenter = presenter;
        title("Edit Event");
        subtitle("Configure event details.");
        saveBtn = new LkBtn("Save Changes").variant(LkBtn.Variant.primary)
            .onClick(e -> saveEvent());
        actions(
            new LkBtn("Discard").variant(LkBtn.Variant.tertiary)
                .onClick(e -> UI.getCurrent().navigate(CompanyEventListView.class)),
            saveBtn
        );
        add(buildSplit());
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        eventId = event.getRouteParameters().get("eventId").orElse(null);
        boolean createMode = eventId == null || "new".equals(eventId);
        if (createMode) {
            title("Create Event");
            subtitle("Create the event as a draft, then configure its venue and publish it.");
            saveBtn.label("Create Event");
            artists.readOnly(false);
            // Linked editors, status and the danger zone all act on a persisted event.
            sideCol.setVisible(false);
        } else {
            artists.readOnly(true);
            loadEvent(eventId);
        }
    }

    private void loadEvent(String id) {
        switch (presenter.load(AuthSession.token(), Integer.parseInt(id))) {
            case EventManagementPresenter.LoadOutcome.Success ok -> applyEvent(ok.event());
            case EventManagementPresenter.LoadOutcome.NotFound ignored ->
                Toasts.failure("Event not found.");
            case EventManagementPresenter.LoadOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case EventManagementPresenter.LoadOutcome.Failure fail ->
                Toasts.failure("Could not load the event — please try again.");
        }
    }

    /** Populates every control from the loaded event — no hardcoded values anywhere. */
    private void applyEvent(EventDetailDTO ev) {
        title.setValue(ev.name() != null ? ev.name() : "");
        category.setValue(ev.category() != null ? ev.category().name() : null);
        artists.setValues(ev.artistsNames());
        if (ev.location() != null) {
            country.setValue(ev.location().country() != null ? ev.location().country() : "");
            city.setValue(ev.location().city() != null ? ev.location().city() : "");
        }
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
        switch (presenter.save(AuthSession.token(),
                new EventUpdateDTO(
                    eventId,
                    blankToNull(title.getValue()),
                    blankToNull(description.getValue()),
                    category.getValue(),                 // null = leave category alone
                    buildLocation(),                     // null = leave location alone
                    buildShowDates()                     // null = leave schedule alone
                ))) {
            case EventManagementPresenter.SaveOutcome.Success ignored ->
                Toasts.success("Event details saved.");
            case EventManagementPresenter.SaveOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case EventManagementPresenter.SaveOutcome.Failure fail ->
                Toasts.failure("Could not save the event — please try again.");
        }
    }

    /** Create flow (eventId == "new"): validate locally, then hand off to the presenter. */
    private void createEvent() {
        String name = blankToNull(title.getValue());
        if (name == null) { Toasts.failure("Please enter a title."); return; }
        if (category.getValue() == null) { Toasts.failure("Please choose a category."); return; }

        String co = country.getValue() == null ? "" : country.getValue().trim();
        String ci = city.getValue() == null ? "" : city.getValue().trim();
        if (co.isBlank() || ci.isBlank()) { Toasts.failure("Please enter both a country and a city."); return; }

        if (start.getValue() == null || end.getValue() == null) {
            Toasts.failure("Please set both a start and an end time.");
            return;
        }
        if (!end.getValue().isAfter(start.getValue())) {
            Toasts.failure("The end time must be after the start time.");
            return;
        }
        // Match the domain ShowDate rule, which rejects a start at or before "now" (not just before).
        LocalDateTime now = LocalDateTime.now();
        if (!start.getValue().isAfter(now)) {
            Toasts.failure("The start time must be in the future.");
            return;
        }

        List<String> artistList = artists.getValues();
        if (artistList.isEmpty()) { Toasts.failure("Please list at least one artist."); return; }

        if (rating.getValue() == null) { Toasts.failure("Please set a rating (0–5)."); return; }

        switch (presenter.create(AuthSession.token(), CurrentCompanies.currentCompanyId(),
                name, blankToNull(description.getValue()), category.getValue(),
                co, ci, start.getValue(), end.getValue(), artistList, rating.getValue())) {
            case EventManagementPresenter.CreateOutcome.Success ok -> {
                Toasts.success("Event created as a draft — configure the venue map and publish when ready.");
                UI.getCurrent().navigate("owner/events/" + ok.eventId());
            }
            case EventManagementPresenter.CreateOutcome.NoCompany ignored ->
                Toasts.failure("You can only create events for a company you own.");
            case EventManagementPresenter.CreateOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case EventManagementPresenter.CreateOutcome.InvalidInput bad ->
                Toasts.failure(bad.reason());
            case EventManagementPresenter.CreateOutcome.Failure fail ->
                Toasts.failure("Could not create the event — please try again.");
        }
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

        title.setRequired(true);
        title.setWidthFull();

        category.setLabel("Category");
        category.setItems(Arrays.stream(EventCategory.values()).map(Enum::name).toList());
        category.setWidthFull();

        country.setWidthFull();
        city.setWidthFull();

        artists.setWidthFull();

        start.setWidthFull();
        end.setWidthFull();

        rating.setMin(0);
        rating.setMax(5);
        // No step constraint: accept any decimal (e.g. 4.1); only out-of-range [0,5] is flagged invalid.
        rating.setRequiredIndicatorVisible(true);
        rating.setWidthFull();

        description.setMinHeight("120px");
        description.setWidthFull();

        Div grid = new Div();
        grid.getStyle()
                .set("display", "grid")
                .set("grid-template-columns", "repeat(auto-fit, minmax(min(100%, 220px), 1fr))")
                .set("gap", "14px");
        grid.add(title, category, country, city, start, end, rating);

        LkCol col = new LkCol().gap(14);
        col.add(grid, artists, description);
        card.add(col);
        return card;
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
        if (eventId != null && !"new".equals(eventId)) {
            UI.getCurrent().navigate("owner/venue/" + eventId);
        }
    }

    private Div linkRow(String iconName, String label, Runnable r) {
        Div row = new Div();
        row.addClassName("ow-link-row");
        row.add(new LkIcon(iconName, 17));
        Span lbl = new Span(label);
        lbl.getStyle().set("flex", "1");
        Span arrow = new Span("→");
        row.add(lbl, arrow);
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
                .onClick(e -> cancelEvent()));
        return card;
    }

    private void cancelEvent() {
        if (eventId == null || "new".equals(eventId)) {
            return;
        }
        switch (presenter.cancel(AuthSession.token(), Integer.parseInt(eventId))) {
            case EventManagementPresenter.CancelOutcome.Success ignored -> {
                Toasts.success("Event cancelled — all holders refunded.");
                UI.getCurrent().navigate(CompanyEventListView.class);
            }
            case EventManagementPresenter.CancelOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case EventManagementPresenter.CancelOutcome.Failure fail ->
                Toasts.failure("Could not cancel the event — please try again.");
        }
    }
}
