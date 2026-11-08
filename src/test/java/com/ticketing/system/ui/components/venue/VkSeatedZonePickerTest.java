package com.ticketing.system.ui.components.venue;

import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.ComponentUtil;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link VkSeatedZonePicker}.
 *
 * <p>Pure JUnit — no Spring context needed, Vaadin component internals
 * are plain Java objects that require no VaadinService to instantiate.
 *
 * <p>Click simulation uses {@link VkSeatedZonePicker#simulateClick(String)},
 * a package-private test hook that mirrors the real click-listener path. That hook repeats the
 * picker's own "only a free seat" check, so the tests that pin the tiles' own listeners fire a
 * click on the tile itself, and read the state the tile shows.
 */
class VkSeatedZonePickerTest {

    private static final List<VkSeatedZonePicker.SeatModel> MIXED_SEATS = List.of(
        new VkSeatedZonePicker.SeatModel("A1", 0,  0,  VkSeat.State.free),
        new VkSeatedZonePicker.SeatModel("A2", 32, 0,  VkSeat.State.free),
        new VkSeatedZonePicker.SeatModel("A3", 64, 0,  VkSeat.State.held),
        new VkSeatedZonePicker.SeatModel("A4", 96, 0,  VkSeat.State.sold)
    );

    // ── construction ────────────────────────────────────────────────────────

    @Test
    void GivenEmptySeatList_WhenConstructed_ThenItHasNoSelection() {
        var picker = new VkSeatedZonePicker(List.of(), null);
        assertFalse(picker.hasSelection());
        assertNull(picker.getSelection());
    }

    // ── initial state ────────────────────────────────────────────────────────

    @Test
    void GivenFreshPicker_WhenConstructed_ThenHasNoSelection() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        assertFalse(picker.hasSelection());
    }

    @Test
    void GivenFreshPicker_WhenGetSelection_ThenReturnsNull() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        assertNull(picker.getSelection());
    }

    // ── click-to-select ──────────────────────────────────────────────────────

    @Test
    void GivenFreeSeat_WhenClicked_ThenSelectedAndHasSelection() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        picker.simulateClick("A1");
        assertTrue(picker.hasSelection());
        assertNotNull(picker.getSelection());
        assertTrue(picker.getSelection().getSeatNumbers().contains("A1"));
    }

    @Test
    void GivenSelectedSeat_WhenClickedAgain_ThenDeselected() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        picker.simulateClick("A1");
        picker.simulateClick("A1");
        assertFalse(picker.hasSelection());
        assertNull(picker.getSelection());
    }

    @Test
    void GivenHeldSeat_WhenClicked_ThenNotSelected() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        picker.simulateClick("A3"); // held
        assertFalse(picker.hasSelection());
    }

    @Test
    void GivenSoldSeat_WhenClicked_ThenNotSelected() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        picker.simulateClick("A4"); // sold
        assertFalse(picker.hasSelection());
    }

    // ── deselect ─────────────────────────────────────────────────────────────

    @Test
    void GivenSelectedSeat_WhenDeselect_ThenRemovedFromSelection() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        picker.simulateClick("A1");
        picker.deselect("A1");
        assertFalse(picker.hasSelection());
        assertNull(picker.getSelection());
    }

    @Test
    void GivenUnknownLabel_WhenDeselect_ThenNoException() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        assertDoesNotThrow(() -> picker.deselect("SEAT_DOES_NOT_EXIST"));
        assertFalse(picker.hasSelection());
    }

    // ── clearSelection ────────────────────────────────────────────────────────

    @Test
    void GivenMultipleSelected_WhenClearSelection_ThenNoneSelected() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        picker.simulateClick("A1");
        picker.simulateClick("A2");
        picker.clearSelection();
        assertFalse(picker.hasSelection());
        assertNull(picker.getSelection());
    }

    @Test
    void GivenNoSelection_WhenClearSelection_ThenNoException() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        assertDoesNotThrow(picker::clearSelection);
    }

    // ── multi-seat selection ───────────────────────────────────────────────────

    @Test
    void GivenTwoSeatsClicked_WhenGetSelection_ThenContainsBoth() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        picker.simulateClick("A1");
        picker.simulateClick("A2");
        var labels = picker.getSelection().getSeatNumbers();
        assertEquals(2, labels.size());
        assertTrue(labels.contains("A1"));
        assertTrue(labels.contains("A2"));
    }

    @Test
    void GivenTwoSeatsClickedInOrder_WhenGetSelection_ThenPreservesInsertionOrder() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        picker.simulateClick("A2");
        picker.simulateClick("A1");
        var labels = picker.getSelection().getSeatNumbers();
        assertEquals("A2", labels.get(0));
        assertEquals("A1", labels.get(1));
    }

    // ── onSelectionChange callback ─────────────────────────────────────────────

    @Test
    void GivenCallback_WhenSeatToggled_ThenCallbackFires() {
        var count = new AtomicInteger(0);
        var picker = new VkSeatedZonePicker(MIXED_SEATS, count::incrementAndGet);
        picker.simulateClick("A1");
        assertEquals(1, count.get());
    }

    @Test
    void GivenCallback_WhenSeatToggledTwice_ThenCallbackFiresTwice() {
        var count = new AtomicInteger(0);
        var picker = new VkSeatedZonePicker(MIXED_SEATS, count::incrementAndGet);
        picker.simulateClick("A1");
        picker.simulateClick("A1");
        assertEquals(2, count.get());
    }

    @Test
    void GivenCallbackAndASelectedSeat_WhenDeselectCalled_ThenCallbackFires() {
        var count = new AtomicInteger(0);
        var picker = new VkSeatedZonePicker(MIXED_SEATS, count::incrementAndGet);
        picker.simulateClick("A1"); // count = 1
        count.set(0);
        picker.deselect("A1");
        assertEquals(1, count.get());
    }

    @Test
    void GivenCallback_WhenDeselectNonexistent_ThenCallbackNotFired() {
        var count = new AtomicInteger(0);
        var picker = new VkSeatedZonePicker(MIXED_SEATS, count::incrementAndGet);
        picker.deselect("DOES_NOT_EXIST");
        assertEquals(0, count.get());
    }

    @Test
    void GivenCallback_WhenHeldSeatClicked_ThenCallbackNotFired() {
        var count = new AtomicInteger(0);
        var picker = new VkSeatedZonePicker(MIXED_SEATS, count::incrementAndGet);
        picker.simulateClick("A3"); // held
        assertEquals(0, count.get());
    }

    @Test
    void GivenCallbackAndASelectedSeat_WhenClearSelectionCalled_ThenCallbackFires() {
        var count = new AtomicInteger(0);
        var picker = new VkSeatedZonePicker(MIXED_SEATS, count::incrementAndGet);
        picker.simulateClick("A1"); // count = 1
        count.set(0);
        picker.clearSelection();
        assertEquals(1, count.get());
    }

    // ---- #189: the tiles' own click listeners, and the state each tile shows ----

    @Test
    void GivenAFreeSeat_WhenAClickReachesItsTile_ThenItIsSelectedAndTheTileReadsMine() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);

        clickTile(picker, "A1");

        assertEquals(List.of("A1"), picker.getSelection().getSeatNumbers());
        assertTileState(picker, "A1", VkSeat.State.mine);
    }

    // The browser sends no click on a held seat's tile, which is disabled; if one arrives anyway, the picker
    // must not take the seat.
    @Test
    void GivenAHeldSeat_WhenAClickReachesItsTile_ThenItIsNotSelectedAndStaysHeld() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);

        clickTile(picker, "A3");

        assertFalse(picker.hasSelection());
        assertTileState(picker, "A3", VkSeat.State.held);
    }

    @Test
    void GivenASelectedSeat_WhenDeselected_ThenItsTileIsFreeAgain() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        clickTile(picker, "A1");

        picker.deselect("A1");

        assertTileState(picker, "A1", VkSeat.State.free);
    }

    @Test
    void GivenTwoSelectedSeats_WhenClearSelection_ThenBothTilesAreFreeAgainAndTheOthersKeepTheirState() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        clickTile(picker, "A1");
        clickTile(picker, "A2");

        picker.clearSelection();

        assertTileState(picker, "A1", VkSeat.State.free);
        assertTileState(picker, "A2", VkSeat.State.free);
        assertTileState(picker, "A3", VkSeat.State.held);
        assertTileState(picker, "A4", VkSeat.State.sold);
    }

    @Test
    void GivenNullCallback_WhenSeatToggledDeselectedAndCleared_ThenDoesNotThrow() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        assertDoesNotThrow(() -> {
            picker.simulateClick("A1");
            picker.deselect("A1");
            picker.clearSelection();
        });
    }

    private static VkSeat tile(VkSeatedZonePicker picker, String label) {
        return picker.getChildren()
            .flatMap(canvas -> canvas.getChildren())
            .filter(VkSeat.class::isInstance)
            .map(VkSeat.class::cast)
            .filter(seat -> label.equals(seat.getText()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no tile for seat " + label));
    }

    // A click event as the tile's listener takes it, fired whatever the tile's state.
    private static void clickTile(VkSeatedZonePicker picker, String label) {
        VkSeat tile = tile(picker, label);
        ComponentUtil.fireEvent(tile, new ClickEvent<>(tile));
    }

    // The tile shows exactly this state's class, and says it: pressed for the buyer's own pick.
    private static void assertTileState(VkSeatedZonePicker picker, String label, VkSeat.State state) {
        VkSeat tile = tile(picker, label);
        List<String> stateClasses = tile.getClassNames().stream().filter(c -> c.startsWith("vk-seat-")).toList();
        assertEquals(List.of("vk-seat-" + state.name()), stateClasses, "seat " + label + "'s state class");
        assertEquals(state == VkSeat.State.mine ? "true" : state == VkSeat.State.free ? "false" : null,
            tile.getElement().getAttribute("aria-pressed"), "seat " + label + "'s aria-pressed");
    }
}
