package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.button;
import static com.ticketing.system.unit.presentation.ViewHarness.forceClick;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.config.VenueLimitsProperties;
import com.ticketing.system.shared.dto.GridPlacementDTO;
import com.ticketing.system.shared.dto.VenueLayoutDTO;
import com.ticketing.system.shared.dto.ZoneDetailDTO;
import com.ticketing.system.ui.presenters.company.VenueMapPresenter;
import com.ticketing.system.ui.views.company.VenueMapEditorView;

/**
 * After the venue map fails to load, the editor holds only its placeholder zone, so Save Map would
 * replace the event's real map with it: Save is disabled and the load error shows (#187, UI-A16).
 */
class VenueMapFailedLoadTest {

    // ---- UI-A16: a failed map load still allows Save (#187) ----

    private static final int EVENT_ID = 7;
    private static final VenueLimitsProperties LIMITS = new VenueLimitsProperties(12, 49_500, 1_000_000, 100_000);

    private ViewHarness harness;
    private VenueMapPresenter presenter;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
        ViewHarness.signInMember();
        presenter = mock(VenueMapPresenter.class);
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenTheMapFailsToLoad_WhenTheEditorOpens_ThenSaveMapIsDisabled() {
        when(presenter.loadZones(anyString(), eq(EVENT_ID)))
            .thenReturn(new VenueMapPresenter.LoadOutcome.Failure("database unreachable"));

        VenueMapEditorView view = openEditor();

        assertFalse(button(view, "Save Map").getElement().isEnabled(), "Save Map must be disabled after a failed load");
    }

    // Kept: the failed load is reported.
    @Test
    void GivenTheMapFailsToLoad_WhenTheEditorOpens_ThenTheLoadErrorShows() {
        when(presenter.loadZones(anyString(), eq(EVENT_ID)))
            .thenReturn(new VenueMapPresenter.LoadOutcome.Failure("database unreachable"));

        openEditor();

        List<String> toasts = harness.toasts();
        assertTrue(toasts.stream().anyMatch(t -> t.contains("database unreachable")),
            "expected the load error in a toast, got: " + toasts);
    }

    // Kept: a map that loaded can be saved.
    @Test
    void GivenTheMapLoads_WhenTheEditorOpens_ThenSaveMapIsEnabled() {
        when(presenter.loadZones(anyString(), eq(EVENT_ID))).thenReturn(new VenueMapPresenter.LoadOutcome.Success(
            new VenueLayoutDTO(3, 3, List.of(
                new ZoneDetailDTO("Floor", false, 0, 0, 500, 50.0, new GridPlacementDTO(1, 1, 1, 1))))));

        VenueMapEditorView view = openEditor();

        assertTrue(button(view, "Save Map").getElement().isEnabled(), "Save Map must be enabled after a successful load");
    }

    // A route id that is no number names no event, so nothing loads and nothing may be saved over it.
    @Test
    void GivenANonNumericEventId_WhenTheEditorOpens_ThenItSaysNotFoundSaveMapIsDisabledAndNothingIsSaved() {
        VenueMapEditorView view = new VenueMapEditorView(presenter, LIMITS);
        harness.attach(view);
        view.beforeEnter(routeTo("eventId", "abc"));

        // Neither the browser nor Flow lets a click reach the disabled Save Map; forced through, the view's own
        // guard must still save nothing.
        forceClick(button(view, "Save Map"));

        assertTrue(harness.toasts().contains("Event not found."), "the visitor must be told: " + harness.toasts());
        assertFalse(button(view, "Save Map").getElement().isEnabled(), "Save Map must be disabled for no event");
        verify(presenter, never()).saveMap(any(), any(), any());
    }

    private VenueMapEditorView openEditor() {
        VenueMapEditorView view = new VenueMapEditorView(presenter, LIMITS);
        view.beforeEnter(routeTo("eventId", String.valueOf(EVENT_ID)));
        return view;
    }
}
