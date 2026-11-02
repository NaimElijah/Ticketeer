package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.config.VenueLimitsProperties;
import com.ticketing.system.catalog.domain.ZoneType;
import com.ticketing.system.sales.config.ReservationHoldProperties;
import com.ticketing.system.shared.dto.InventoryZoneDTO;
import com.ticketing.system.shared.dto.SeatDTO;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.venue.VkSeat;
import com.ticketing.system.ui.components.venue.VkVenueMap;
import com.ticketing.system.ui.presenters.catalog.SeatPickerPresenter;
import com.ticketing.system.ui.presenters.company.VenueMapPresenter;
import com.ticketing.system.ui.views.catalog.SeatPickerView;
import com.ticketing.system.ui.views.company.VenueMapEditorView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.dom.Element;

/**
 * A seat's and a zone's state reach a screen reader, not only their colour, and pictures of seats are not
 * stops on the Tab path (#188, U-A14, P-M3, P-M4, U-B3; WCAG 1.4.1, 4.1.2, 2.4.3):
 * <ul>
 *   <li>a seat's name says its state (free, selected, held, sold); a selected seat is a pressed toggle
 *       ({@code aria-pressed=true}); a held or sold seat is disabled or {@code aria-disabled};</li>
 *   <li>the venue map's zone buttons say which zone is selected ({@code aria-pressed});</li>
 *   <li>the venue editor's seat preview and the seat picker's colour key draw seats nobody can pick, so
 *       they are not buttons, or are {@code tabindex=-1} and {@code aria-hidden}.</li>
 * </ul>
 */
class SeatAndZoneStateTest {

    // ---- F12: seats and zones (#188) ----

    private static final Map<VkSeat.State, Pattern> STATE_WORDS = Map.of(
        VkSeat.State.free, Pattern.compile("(?i)\\b(free|available)\\b"),
        VkSeat.State.mine, Pattern.compile("(?i)\\b(selected|mine|in your order)\\b"),
        VkSeat.State.held, Pattern.compile("(?i)\\b(held|locked|reserved)\\b"),
        VkSeat.State.sold, Pattern.compile("(?i)\\b(sold|unavailable)\\b"));
    private static final String SEAT_CLASS = "vk-seat";

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- VkSeat ----

    @Test
    void GivenASeatInEachState_WhenItRenders_ThenItsNameSaysItsNumberAndState() {
        for (VkSeat.State state : VkSeat.State.values()) {
            Element seat = new VkSeat(state, "7").getElement();

            String name = A11y.name(seat);
            assertTrue(name.contains("7") && STATE_WORDS.get(state).matcher(name).find(),
                "a " + state + " seat must be named by its number and its state: " + A11y.describe(seat));
        }
    }

    @Test
    void GivenAFreeSeat_WhenItIsSelected_ThenItIsAPressedToggleNamedSelected() {
        VkSeat seat = new VkSeat(VkSeat.State.free, "7");
        assertEquals("false", seat.getElement().getAttribute("aria-pressed"),
            "a free seat is a toggle that is not pressed: " + A11y.describe(seat.getElement()));

        seat.setState(VkSeat.State.mine);

        assertEquals("true", seat.getElement().getAttribute("aria-pressed"),
            "a selected seat is a pressed toggle: " + A11y.describe(seat.getElement()));
        assertTrue(STATE_WORDS.get(VkSeat.State.mine).matcher(A11y.name(seat.getElement())).find(),
            "a selected seat's name must say so: " + A11y.describe(seat.getElement()));
    }

    @Test
    void GivenASelectedSeat_WhenItIsDeselected_ThenItIsNoLongerPressedAndNamedFree() {
        VkSeat seat = new VkSeat(VkSeat.State.mine, "7");

        seat.setState(VkSeat.State.free);

        assertEquals("false", seat.getElement().getAttribute("aria-pressed"),
            "a deselected seat is not pressed: " + A11y.describe(seat.getElement()));
        assertTrue(STATE_WORDS.get(VkSeat.State.free).matcher(A11y.name(seat.getElement())).find(),
            "a deselected seat's name must say it is free: " + A11y.describe(seat.getElement()));
    }

    @Test
    void GivenHeldAndSoldSeats_WhenTheyRender_ThenTheyAreDisabledForAssistiveTechnology() {
        for (VkSeat.State state : List.of(VkSeat.State.held, VkSeat.State.sold)) {
            Element seat = new VkSeat(state, "7").getElement();

            assertTrue(A11y.disabledForAt(seat),
                "a " + state + " seat cannot be picked, so it is disabled or aria-disabled: " + A11y.describe(seat));
        }
    }

    // ---- VkVenueMap ----

    @Test
    void GivenAVenueMapWithFloorSelected_WhenItRenders_ThenOnlyTheFloorZoneIsPressed() {
        VkVenueMap map = new VkVenueMap(List.of(
            new VkVenueMap.Zone("floor", "Floor", "$50", "primary", null, "0", "0", "50%", "50%"),
            new VkVenueMap.Zone("balcony", "Balcony", "$30", "primary", null, "0", "50%", "50%", "50%")),
            "floor", false, id -> { });

        assertEquals("true", zone(map, "Floor").getAttribute("aria-pressed"),
            "the selected zone must say so: " + A11y.describe(zone(map, "Floor")));
        assertEquals("false", zone(map, "Balcony").getAttribute("aria-pressed"),
            "a zone not selected is a toggle that is not pressed: " + A11y.describe(zone(map, "Balcony")));
    }

    // ---- seats drawn for show ----

    @Test
    void GivenTheVenueEditor_WhenItPreviewsAZonesSeats_ThenThePreviewSeatsAreNotTabStops() {
        ViewHarness.signInMember();
        VenueMapEditorView view = new VenueMapEditorView(mock(VenueMapPresenter.class),
            new VenueLimitsProperties(12, 49_500, 1_000_000, 100_000));

        assertSeatsAreNotTabStops(view, "the venue editor's seat preview");
    }

    @Test
    void GivenTheSeatPicker_WhenItShowsItsSeatColourKey_ThenTheKeysSeatsAreNotTabStops() {
        SeatPickerPresenter presenter = mock(SeatPickerPresenter.class);
        when(presenter.loadZone(anyInt(), anyInt())).thenReturn(new SeatPickerPresenter.LoadOutcome.Loaded(
            new InventoryZoneDTO(8, "Floor", "SEATED", 1, 1, 0, 0, 10.0,
                List.of(new SeatDTO("A1", 1, 0, "AVAILABLE")), null), ZoneType.SEATED));
        SeatPickerView view = new SeatPickerView(presenter, new ReservationHoldProperties(10));
        view.beforeEnter(routeTo("eventId", "5", "zoneId", "8"));

        LkCard key = ViewHarness.find(view, LkCard.class).stream()
            .filter(card -> A11y.text(card.getElement()).contains("Race-Condition States"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the seat picker has no seat colour key"));

        assertSeatsAreNotTabStops(key, "the seat picker's colour key");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static Element zone(VkVenueMap map, String label) {
        return A11y.elements(map, e -> "button".equals(A11y.role(e)) && A11y.name(e).contains(label)).stream()
            .findFirst()
            .orElseThrow(() -> new AssertionError("no zone button \"" + label + "\""));
    }

    // Each drawn seat is out of the Tab order and, if it is still a button, hidden from screen readers.
    private static void assertSeatsAreNotTabStops(Component root, String where) {
        List<Element> seats = A11y.elements(root, e -> e.getClassList().contains(SEAT_CLASS));
        assertFalse(seats.isEmpty(), where + " draws no ." + SEAT_CLASS + " seats");
        List<String> tabStops = seats.stream()
            .filter(seat -> A11y.focusable(seat) || ("button".equals(A11y.role(seat)) && !A11y.ariaHidden(seat)))
            .map(A11y::describe)
            .toList();
        assertTrue(tabStops.isEmpty(), where + " draws " + tabStops.size() + " of " + seats.size()
            + " seats as buttons nobody can use; e.g. " + tabStops.stream().limit(3).toList());
    }
}
