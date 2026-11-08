package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.button;
import static com.ticketing.system.unit.presentation.ViewHarness.buttons;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.field;
import static com.ticketing.system.unit.presentation.ViewHarness.find;
import static com.ticketing.system.unit.presentation.ViewHarness.founderOf;
import static com.ticketing.system.unit.presentation.ViewHarness.pageTitle;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static com.ticketing.system.unit.presentation.ViewHarness.shown;
import static com.ticketing.system.unit.presentation.ViewHarness.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.shared.dto.InvitationDTO;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.presenters.account.MyInvitationsPresenter;
import com.ticketing.system.ui.presenters.company.EventManagementPresenter;
import com.ticketing.system.ui.views.account.MyInvitationsView;
import com.ticketing.system.ui.views.company.EventManagementView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;

/**
 * A view that navigates to the route it is already on gets nothing from Vaadin: navigating to the
 * current location is ignored, and a new route parameter reuses the instance. So the view reloads or
 * resets in place (#187, UI-A10).
 */
class ReloadInPlaceTest {

    // ---- UI-A10: navigating to the current location does nothing (#187) ----

    private static final int NEW_EVENT_ID = 42;

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
        ViewHarness.signInMember();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- My invitations ----

    @Test
    void GivenAPendingOwnerInvitation_WhenTheMemberAcceptsIt_ThenTheInvitationsLoadAgain() {
        MyInvitationsPresenter presenter = invitationsPresenter("Owner");
        MyInvitationsView view = new MyInvitationsView(presenter, InboxViewHarness.displayTime());
        harness.attach(view);

        click(button(view, "Accept"));

        verify(presenter, times(2)).load(any());
    }

    // Kept: a manager invitation already reloads in place.
    @Test
    void GivenAPendingManagerInvitation_WhenTheMemberAcceptsIt_ThenTheInvitationsLoadAgain() {
        MyInvitationsPresenter presenter = invitationsPresenter("Manager");
        MyInvitationsView view = new MyInvitationsView(presenter, InboxViewHarness.displayTime());
        harness.attach(view);

        click(button(view, "Accept"));

        verify(presenter, times(2)).load(any());
    }

    // ---- the event page, from create to edit ----
    //
    // After a create the view navigates to the new event's route, and Vaadin enters the same instance
    // again with the new id.

    @Test
    void GivenTheCreatePage_WhenTheSameViewIsEnteredForTheCreatedEvent_ThenItReadsAsTheEditPage() {
        EventManagementView view = enteredForCreateThenForTheNewEvent();

        assertEquals("Edit Event", pageTitle(view));
        assertFalse(buttons(view, "Save Changes").isEmpty(), "the primary action must read Save Changes: " + text(view));
    }

    @Test
    void GivenTheCreatePage_WhenTheSameViewIsEnteredForTheCreatedEvent_ThenTheEventsSideColumnShows() {
        EventManagementView view = enteredForCreateThenForTheNewEvent();

        LkCard linkedEditors = find(view, LkCard.class).stream()
            .filter(card -> card.getElement().getTextRecursively().startsWith("Linked Editors"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the event page has no Linked Editors card"));
        assertTrue(shown(linkedEditors), "a saved event's linked editors, status and danger zone must show");
    }

    // An edit save leaves a blank field alone, so the edit page must not claim the create page's rule (#188).
    @Test
    void GivenTheCreatePage_WhenTheSameViewIsEnteredForTheCreatedEvent_ThenTheCreateOnlyFieldsShowNoRequiredIndicator() {
        EventManagementView view = enteredForCreateThenForTheNewEvent();

        for (Component f : createOnlyFields(view)) {
            assertFalse(A11y.showsRequired(f), "an edit save treats a blank field as \"leave alone\", so "
                + "it must show no required indicator: " + A11y.describe(f.getElement()));
        }
    }

    // The reverse: the edit page cleared the indicators, so the create page entered next must set them again.
    @Test
    void GivenAnEditPage_WhenTheSameViewIsEnteredForANewEvent_ThenTheCreateOnlyFieldsShowARequiredIndicator() {
        EventManagementView view = enteredForAnEventThenForANewOne();

        for (Component f : createOnlyFields(view)) {
            assertTrue(A11y.showsRequired(f), "creating an event refuses a blank field, so it must show a "
                + "required indicator: " + A11y.describe(f.getElement()));
        }
    }

    // ---- arrangement ----

    private static MyInvitationsPresenter invitationsPresenter(String role) {
        MyInvitationsPresenter presenter = mock(MyInvitationsPresenter.class);
        InvitationDTO invitation = new InvitationDTO("a-1", 3, "Acme", role, "bob", List.of(), "PENDING",
            LocalDateTime.of(2027, 7, 1, 12, 0));
        when(presenter.load(any())).thenReturn(new MyInvitationsPresenter.Outcome.Success(List.of(invitation), List.of()));
        when(presenter.accept(any(), anyInt())).thenReturn(new MyInvitationsPresenter.ActionOutcome.Success());
        return presenter;
    }

    private static List<Component> createOnlyFields(EventManagementView view) {
        return List.of(
            field(view, Select.class, "Category"),
            field(view, TextField.class, "Country"),
            field(view, TextField.class, "City"),
            field(view, DateTimePicker.class, "Starts"),
            field(view, DateTimePicker.class, "Ends"));
    }

    private EventManagementView enteredForAnEventThenForANewOne() {
        EventManagementView view = enteredForCreateThenForTheNewEvent();
        view.beforeEnter(routeTo("eventId", "new"));
        return view;
    }

    private EventManagementView enteredForCreateThenForTheNewEvent() {
        harness.memberships(founderOf(3, "Acme"));
        EventManagementPresenter presenter = mock(EventManagementPresenter.class);
        when(presenter.defaultTimeZone()).thenReturn(ZoneOffset.UTC);
        when(presenter.load(any(), eq(NEW_EVENT_ID))).thenReturn(new EventManagementPresenter.LoadOutcome.Success(
            new EventDetailDTO(String.valueOf(NEW_EVENT_ID), "Coldplay", 4.5, "Live", EventCategory.CONCERT, null,
                "3", "Acme", EventStatus.DRAFT, List.of(), ZoneOffset.UTC, List.of("Coldplay"), 0.0)));
        EventManagementView view = new EventManagementView(presenter);

        view.beforeEnter(routeTo("eventId", "new"));
        view.beforeEnter(routeTo("eventId", String.valueOf(NEW_EVENT_ID)));
        return view;
    }
}
