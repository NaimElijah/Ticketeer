package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.button;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.find;
import static com.ticketing.system.unit.presentation.ViewHarness.founderOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.components.kit.LkTextRows;
import com.ticketing.system.ui.presenters.catalog.BrowseEventsPresenter;
import com.ticketing.system.ui.presenters.company.CompanyEventListPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.catalog.BrowseEventsView;
import com.ticketing.system.ui.views.company.CompanyEventListView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.dom.Element;

/**
 * Every input says what it is for, to a screen reader as well as by sight (#188, U-A22, P-M6, A-M2;
 * WCAG 1.3.1, 3.3.2, 4.1.2), the filters on Browse and My Events included. A span beside a field that is
 * tied to nothing, or a placeholder that vanishes once the user types, names nothing: a screen reader
 * would announce "edit text", and the price and rating bounds as two unnamed fields. Each field must
 * have an accessible name (a label, an aria-label or aria-labelledby), and each min/max pair must sit in a
 * {@code role=group} named for what it bounds, so "Min" is heard as "Price range, Min".
 */
class ProgrammaticLabelsTest {

    // ---- F15: programmatic labels (#188) ----

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- BrowseEventsView filters ----

    @Test
    void GivenBrowse_WhenItRenders_ThenEachTextFilterIsNamedForWhatItFilters() {
        List<TextField> fields = find(browse(), TextField.class);

        assertEquals(3, fields.size(), "Browse has three text filters: event name, keywords, artist");
        assertNamed(fields.get(0), "Event name");
        assertNamed(fields.get(1), "Keywords");
        assertNamed(fields.get(2), "Artist");
    }

    @Test
    void GivenBrowse_WhenItRenders_ThenThePriceBoundsAreNamedInANamedPriceGroup() {
        List<IntegerField> bounds = find(browse(), IntegerField.class);

        assertBoundsInGroup(bounds.get(0), bounds.get(1), "Price");
    }

    @Test
    void GivenBrowse_WhenItRenders_ThenTheEventRatingBoundsAreNamedInANamedEventRatingGroup() {
        List<NumberField> bounds = find(browse(), NumberField.class);

        assertBoundsInGroup(bounds.get(0), bounds.get(1), "Event rating");
    }

    @Test
    void GivenBrowse_WhenItRenders_ThenTheOrganizerRatingBoundsAreNamedInANamedOrganizerRatingGroup() {
        List<NumberField> bounds = find(browse(), NumberField.class);

        assertBoundsInGroup(bounds.get(2), bounds.get(3), "Organizer rating");
    }

    // ---- CompanyEventListView filters ----

    @Test
    void GivenMyEvents_WhenItRenders_ThenEachTextFilterIsNamedForWhatItFilters() {
        List<TextField> fields = find(myEvents(), TextField.class);

        assertEquals(3, fields.size(), "My Events has three text filters: event name, keywords, artist");
        assertNamed(fields.get(0), "Event name");
        assertNamed(fields.get(1), "Keywords");
        assertNamed(fields.get(2), "Artist");
    }

    @Test
    void GivenMyEvents_WhenItRenders_ThenTheCustomRangeDatePickersAreNamed() {
        List<DatePicker> pickers = find(myEvents(), DatePicker.class);

        assertEquals(2, pickers.size(), "My Events has a start and an end date picker for a custom range");
        assertNamedMatching(pickers.get(0), Pattern.compile("(?i)\\b(start|from)\\b"), "start date");
        assertNamedMatching(pickers.get(1), Pattern.compile("(?i)\\b(end|to)\\b"), "end date");
    }

    @Test
    void GivenMyEvents_WhenItRenders_ThenThePriceBoundsAreNamedInANamedPriceGroup() {
        List<IntegerField> bounds = find(myEvents(), IntegerField.class);

        assertBoundsInGroup(bounds.get(0), bounds.get(1), "Price");
    }

    @Test
    void GivenMyEvents_WhenItRenders_ThenTheRatingBoundsAreNamedInANamedRatingGroup() {
        List<NumberField> bounds = find(myEvents(), NumberField.class);

        assertBoundsInGroup(bounds.get(0), bounds.get(1), "rating");
    }

    @Test
    void GivenADraftEvent_WhenTheOwnerOpensItsStatusDialog_ThenTheStatusSelectIsNamed() {
        CompanyEventListView view = myEvents();

        click(button(view, "Change status"));

        List<Select> selects = harness.dialogs().stream()
            .flatMap(dialog -> find(dialog, Select.class).stream())
            .toList();
        assertFalse(selects.isEmpty(), "the status dialog has no select");
        assertNamedMatching(selects.get(0), Pattern.compile("(?i)status"), "status");
    }

    // ---- LkTextRows ----

    @Test
    void GivenTwoArtistRows_WhenTheyRender_ThenEachRowFieldIsNamedByTheLabelAndItsPosition() {
        LkTextRows rows = new LkTextRows("Artists", "+ Add artist");
        click(button(rows, "+ Add artist"));

        List<TextField> fields = find(rows, TextField.class);

        assertEquals(2, fields.size(), "adding a row gives two artist fields");
        assertNamedMatching(fields.get(0), Pattern.compile("(?i)artists?\\D*\\b1\\b"), "Artists 1");
        assertNamedMatching(fields.get(1), Pattern.compile("(?i)artists?\\D*\\b2\\b"), "Artists 2");
    }

    // ---- assertions ------------------------------------------------------------------------------

    private static void assertNamed(Component field, String expected) {
        String name = A11y.name(field.getElement());
        assertTrue(name.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT)),
            "the field must be named \"" + expected + "\" (setLabel, setAriaLabel or aria-labelledby), not only by a "
                + "nearby span or a placeholder; its name is \"" + name + "\": " + A11y.describe(field.getElement()));
    }

    private static void assertNamedMatching(Component field, Pattern expected, String what) {
        String name = A11y.name(field.getElement());
        assertTrue(expected.matcher(name).find(),
            "the field must be named for its " + what + " (setLabel, setAriaLabel or aria-labelledby); its name is \""
                + name + "\": " + A11y.describe(field.getElement()));
    }

    private static void assertBoundsInGroup(Component min, Component max, String groupName) {
        assertNamedMatching(min, Pattern.compile("(?i)\\bmin"), "lower bound (\"Min\")");
        assertNamedMatching(max, Pattern.compile("(?i)\\bmax"), "upper bound (\"Max\")");
        for (Component bound : List.of(min, max)) {
            Element group = group(bound.getElement())
                .orElseThrow(() -> new AssertionError("the " + groupName + " bounds must sit in a role=group (or a "
                    + "fieldset) named \"" + groupName + "\": " + A11y.describe(bound.getElement())));
            String name = groupName(group);
            assertTrue(name.toLowerCase(Locale.ROOT).contains(groupName.toLowerCase(Locale.ROOT)),
                "the group around the bounds must be named \"" + groupName + "\"; it is named \"" + name + "\": "
                    + A11y.describe(group));
        }
    }

    private static Optional<Element> group(Element field) {
        for (Element a = field.getParent(); a != null; a = a.getParent()) {
            if ("group".equals(A11y.role(a)) || "fieldset".equals(a.getTag())) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    private static String groupName(Element group) {
        String name = A11y.name(group);
        if (!name.isBlank()) {
            return name;
        }
        return group.getChildren()
            .filter(child -> "legend".equals(child.getTag()))
            .map(A11y::text)
            .findFirst()
            .orElse("");
    }

    // ---- arrangement -----------------------------------------------------------------------------

    private static BrowseEventsView browse() {
        BrowseEventsPresenter presenter = mock(BrowseEventsPresenter.class);
        when(presenter.search(any(), any())).thenReturn(new ListOutcome.Loaded<>(List.of()));
        when(presenter.countries()).thenReturn(new ListOutcome.Loaded<>(List.of("Israel")));
        return new BrowseEventsView(presenter, mock(SessionIdentity.class), InboxViewHarness.displayTime());
    }

    private CompanyEventListView myEvents() {
        ViewHarness.signInMember();
        harness.memberships(founderOf(3, "Acme"));
        CompanyEventListPresenter presenter = mock(CompanyEventListPresenter.class);
        when(presenter.load(any(), any(), any()))
            .thenReturn(new CompanyEventListPresenter.Outcome.Success(List.of(draftEvent())));
        when(presenter.countries(any(), any())).thenReturn(new ListOutcome.Loaded<>(List.of()));
        return new CompanyEventListView(presenter, InboxViewHarness.displayTime());
    }

    private static EventDetailDTO draftEvent() {
        return new EventDetailDTO("9", "Othello", 4.5, "Tragedy", EventCategory.THEATER, null, "3", "Acme",
            EventStatus.DRAFT, List.of(), ZoneOffset.UTC, List.of("Cast"), 0.0);
    }
}
