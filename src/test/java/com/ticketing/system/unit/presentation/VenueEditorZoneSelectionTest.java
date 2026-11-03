package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.button;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.config.VenueLimitsProperties;
import com.ticketing.system.ui.presenters.company.VenueMapPresenter;
import com.ticketing.system.ui.views.company.VenueMapEditorView;
import com.vaadin.flow.dom.Element;

/**
 * A keyboard user can select a zone to edit in the venue editor, and a screen reader hears which zone is
 * selected (#188, P-M3; WCAG 2.1.1, 4.1.2): each zone in the editor's list is a button, pressed while its
 * zone is the one being edited.
 */
class VenueEditorZoneSelectionTest {

    // ---- F12: the venue editor's zone list (#188) ----

    private static final String FIRST = "Zone 1";
    private static final String SECOND = "Zone 2";

    private ViewHarness harness;
    private VenueMapEditorView view;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
        ViewHarness.signInMember();
        VenueMapPresenter presenter = mock(VenueMapPresenter.class);
        when(presenter.defaultZoneName(any())).thenReturn(SECOND);
        view = new VenueMapEditorView(presenter, new VenueLimitsProperties(12, 49_500, 1_000_000, 100_000));
        click(button(view, "+ Standing"));
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenTwoZonesWithTheNewOneSelected_WhenTheEditorRenders_ThenOnlyItsZoneButtonIsPressed() {
        assertPressed(SECOND, "true");
        assertPressed(FIRST, "false");
    }

    @Test
    void GivenTheSecondZoneSelected_WhenTheUserActivatesTheFirstZone_ThenTheFirstZoneIsPressed() {
        Element first = zoneButtons(FIRST).get(0);

        A11y.fireBubbling(first, view.getElement(), "click");

        assertPressed(FIRST, "true");
        assertPressed(SECOND, "false");
    }

    // Every button named exactly by the zone: the list's, and the map preview's when it names the zone
    // alone. They must agree on which zone is selected.
    private void assertPressed(String zone, String pressed) {
        List<Element> buttons = zoneButtons(zone);
        buttons.forEach(b -> assertEquals(pressed, b.getAttribute("aria-pressed"),
            "the zone \"" + zone + "\" must say whether it is selected: " + A11y.describe(b)));
    }

    private List<Element> zoneButtons(String zone) {
        List<Element> buttons = A11y.elements(view, e -> A11y.isButton(e) && zone.equals(A11y.name(e)));
        assertFalse(buttons.isEmpty(), "the zone \"" + zone + "\" must be a button named by the zone; buttons: "
            + A11y.elements(view, A11y::isButton).stream().map(A11y::describe).toList());
        return buttons;
    }
}
