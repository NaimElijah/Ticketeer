package com.ticketing.system.ui.components.venue;

import org.junit.jupiter.api.Test;

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
 * a package-private test hook that mirrors the real click-listener path.
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
    void givenEmptySeatList_constructsWithoutError() {
        assertDoesNotThrow(() -> new VkSeatedZonePicker(List.of(), null));
    }

    @Test
    void givenMixedStates_constructsWithoutError() {
        assertDoesNotThrow(() -> new VkSeatedZonePicker(MIXED_SEATS, null));
    }

    // ── initial state ────────────────────────────────────────────────────────

    @Test
    void givenFreshPicker_hasNoSelection() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        assertFalse(picker.hasSelection());
    }

    @Test
    void givenFreshPicker_getSelectionReturnsNull() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        assertNull(picker.getSelection());
    }

    // ── click-to-select ──────────────────────────────────────────────────────

    @Test
    void givenFreeSeat_whenClicked_thenSelectedAndHasSelection() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        picker.simulateClick("A1");
        assertTrue(picker.hasSelection());
        assertNotNull(picker.getSelection());
        assertTrue(picker.getSelection().getSeatNumbers().contains("A1"));
    }

    @Test
    void givenSelectedSeat_whenClickedAgain_thenDeselected() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        picker.simulateClick("A1");
        picker.simulateClick("A1");
        assertFalse(picker.hasSelection());
        assertNull(picker.getSelection());
    }

    @Test
    void givenHeldSeat_whenClicked_thenNotSelected() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        picker.simulateClick("A3"); // held
        assertFalse(picker.hasSelection());
    }

    @Test
    void givenSoldSeat_whenClicked_thenNotSelected() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        picker.simulateClick("A4"); // sold
        assertFalse(picker.hasSelection());
    }

    // ── deselect ─────────────────────────────────────────────────────────────

    @Test
    void givenSelectedSeat_whenDeselect_thenRemovedFromSelection() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        picker.simulateClick("A1");
        picker.deselect("A1");
        assertFalse(picker.hasSelection());
        assertNull(picker.getSelection());
    }

    @Test
    void givenUnknownLabel_whenDeselect_thenNoException() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        assertDoesNotThrow(() -> picker.deselect("SEAT_DOES_NOT_EXIST"));
        assertFalse(picker.hasSelection());
    }

    // ── clearSelection ────────────────────────────────────────────────────────

    @Test
    void givenMultipleSelected_whenClearSelection_thenNoneSelected() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        picker.simulateClick("A1");
        picker.simulateClick("A2");
        picker.clearSelection();
        assertFalse(picker.hasSelection());
        assertNull(picker.getSelection());
    }

    @Test
    void givenEmptyPicker_whenClearSelection_thenNoException() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        assertDoesNotThrow(picker::clearSelection);
    }

    // ── multi-seat selection ───────────────────────────────────────────────────

    @Test
    void givenTwoSeatsClicked_getSelectionContainsBoth() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        picker.simulateClick("A1");
        picker.simulateClick("A2");
        var labels = picker.getSelection().getSeatNumbers();
        assertEquals(2, labels.size());
        assertTrue(labels.contains("A1"));
        assertTrue(labels.contains("A2"));
    }

    @Test
    void givenTwoSeatsClickedInOrder_selectionPreservesInsertionOrder() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        picker.simulateClick("A2");
        picker.simulateClick("A1");
        var labels = picker.getSelection().getSeatNumbers();
        assertEquals("A2", labels.get(0));
        assertEquals("A1", labels.get(1));
    }

    // ── onSelectionChange callback ─────────────────────────────────────────────

    @Test
    void givenCallback_whenSeatToggled_callbackFires() {
        var count = new AtomicInteger(0);
        var picker = new VkSeatedZonePicker(MIXED_SEATS, count::incrementAndGet);
        picker.simulateClick("A1");
        assertEquals(1, count.get());
    }

    @Test
    void givenCallback_whenSeatToggledTwice_callbackFiresTwice() {
        var count = new AtomicInteger(0);
        var picker = new VkSeatedZonePicker(MIXED_SEATS, count::incrementAndGet);
        picker.simulateClick("A1");
        picker.simulateClick("A1");
        assertEquals(2, count.get());
    }

    @Test
    void givenCallback_whenDeselectCalled_callbackFires() {
        var count = new AtomicInteger(0);
        var picker = new VkSeatedZonePicker(MIXED_SEATS, count::incrementAndGet);
        picker.simulateClick("A1"); // count = 1
        count.set(0);
        picker.deselect("A1");
        assertEquals(1, count.get());
    }

    @Test
    void givenCallback_whenDeselectNonexistent_callbackNotFired() {
        var count = new AtomicInteger(0);
        var picker = new VkSeatedZonePicker(MIXED_SEATS, count::incrementAndGet);
        picker.deselect("DOES_NOT_EXIST");
        assertEquals(0, count.get());
    }

    @Test
    void givenCallback_whenHeldSeatClicked_callbackNotFired() {
        var count = new AtomicInteger(0);
        var picker = new VkSeatedZonePicker(MIXED_SEATS, count::incrementAndGet);
        picker.simulateClick("A3"); // held
        assertEquals(0, count.get());
    }

    @Test
    void givenCallback_whenClearSelectionCalled_callbackFires() {
        var count = new AtomicInteger(0);
        var picker = new VkSeatedZonePicker(MIXED_SEATS, count::incrementAndGet);
        picker.simulateClick("A1"); // count = 1
        count.set(0);
        picker.clearSelection();
        assertEquals(1, count.get());
    }

    @Test
    void givenNullCallback_noNullPointerOnToggle() {
        var picker = new VkSeatedZonePicker(MIXED_SEATS, null);
        assertDoesNotThrow(() -> {
            picker.simulateClick("A1");
            picker.deselect("A1");
            picker.clearSelection();
        });
    }
}
