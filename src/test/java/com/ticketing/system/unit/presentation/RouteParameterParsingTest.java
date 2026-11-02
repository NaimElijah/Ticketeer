package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.button;
import static com.ticketing.system.unit.presentation.ViewHarness.founderOf;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static com.ticketing.system.unit.presentation.ViewHarness.shown;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.config.VenueLimitsProperties;
import com.ticketing.system.ui.presenters.company.EventManagementPresenter;
import com.ticketing.system.ui.presenters.company.VenueMapPresenter;
import com.ticketing.system.ui.views.company.EventManagementView;
import com.ticketing.system.ui.views.company.VenueMapEditorView;

/**
 * A route parameter is typed by the visitor, so a non-numeric event id shows not-found or redirects;
 * it never throws out of the view (#187, UI-A15). Which of the two the view does is the fix's choice.
 */
class RouteParameterParsingTest {

    // ---- UI-A15: route parameters are parsed unguarded (#187) ----

    private static final String NOT_AN_ID = "abc";

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

    @Test
    void GivenANonNumericEventId_WhenTheVenueEditorIsEntered_ThenNothingThrowsAndNothingIsLoaded() {
        VenueMapPresenter presenter = mock(VenueMapPresenter.class);
        VenueMapEditorView view = new VenueMapEditorView(presenter,
            new VenueLimitsProperties(12, 49_500, 1_000_000, 100_000));

        assertDoesNotThrow(() -> view.beforeEnter(routeTo("eventId", NOT_AN_ID)));
        verify(presenter, never()).loadZones(any(), anyInt());
    }

    @Test
    void GivenANonNumericEventId_WhenTheEventPageIsEntered_ThenNothingThrowsAndNothingIsLoaded() {
        EventManagementPresenter presenter = mock(EventManagementPresenter.class);
        EventManagementView view = new EventManagementView(presenter);

        assertDoesNotThrow(() -> view.beforeEnter(routeTo("eventId", NOT_AN_ID)));
        verify(presenter, never()).load(any(), anyInt());
    }

    // The page names no event, so it says so and offers nothing that would act on one: no save, and no
    // cancel, whose confirm would parse the id again.
    @Test
    void GivenANonNumericEventId_WhenTheEventPageIsEntered_ThenItSaysNotFoundAndOffersNoEventAction() {
        harness.memberships(founderOf(3, "Acme"));
        EventManagementView view = new EventManagementView(mock(EventManagementPresenter.class));

        view.beforeEnter(routeTo("eventId", NOT_AN_ID));

        assertTrue(harness.toasts().contains("Event not found."), "the visitor must be told: " + harness.toasts());
        assertFalse(button(view, "Save Changes").getElement().isEnabled(), "Save must be off for an event that does not exist");
        assertFalse(shown(button(view, "Cancel This Event")), "Cancel must not be offered for an event that does not exist");
    }
}
