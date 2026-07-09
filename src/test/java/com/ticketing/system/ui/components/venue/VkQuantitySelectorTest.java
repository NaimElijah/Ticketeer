package com.ticketing.system.ui.components.venue;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link VkQuantitySelector}.
 *
 * <p>Pure JUnit — no Spring context. Uses the package-private
 * {@link VkQuantitySelector#increment()} and {@link VkQuantitySelector#decrement()}
 * hooks to drive state without needing a real click event.
 */
class VkQuantitySelectorTest {

    // ── construction ────────────────────────────────────────────────────────

    @Test
    void givenPositiveAvailable_constructsWithoutError() {
        assertDoesNotThrow(() -> new VkQuantitySelector(100, 9000, null));
    }

    @Test
    void givenZeroAvailable_constructsWithoutError() {
        assertDoesNotThrow(() -> new VkQuantitySelector(0, 9000, null));
    }

    // ── initial state ────────────────────────────────────────────────────────

    @Test
    void givenPositiveAvailable_initialQuantityIsOne() {
        var sel = new VkQuantitySelector(100, 9000, null);
        assertEquals(1, sel.getQuantity());
    }

    @Test
    void givenPositiveAvailable_hasSelection() {
        var sel = new VkQuantitySelector(100, 9000, null);
        assertTrue(sel.hasSelection());
    }

    @Test
    void givenPositiveAvailable_getSelectionReturnsStandingDto() {
        var sel = new VkQuantitySelector(100, 9000, null);
        assertNotNull(sel.getSelection());
        assertTrue(sel.getSelection().isStandingSelection());
    }

    @Test
    void givenZeroAvailable_quantityIsZero() {
        var sel = new VkQuantitySelector(0, 9000, null);
        assertEquals(0, sel.getQuantity());
    }

    @Test
    void givenZeroAvailable_hasNoSelection() {
        var sel = new VkQuantitySelector(0, 9000, null);
        assertFalse(sel.hasSelection());
    }

    @Test
    void givenZeroAvailable_getSelectionReturnsNull() {
        var sel = new VkQuantitySelector(0, 9000, null);
        assertNull(sel.getSelection());
    }

    // ── increment ─────────────────────────────────────────────────────────────

    @Test
    void givenBelowMax_whenIncrement_quantityIncreases() {
        var sel = new VkQuantitySelector(5, 9000, null);
        sel.increment();
        assertEquals(2, sel.getQuantity());
    }

    @Test
    void givenAtMax_whenIncrement_quantityStaysAtMax() {
        var sel = new VkQuantitySelector(1, 9000, null); // starts at 1, max = 1
        sel.increment();
        assertEquals(1, sel.getQuantity());
    }

    @Test
    void givenMultipleIncrements_quantityMatchesCount() {
        var sel = new VkQuantitySelector(10, 9000, null);
        sel.increment();
        sel.increment();
        sel.increment();
        assertEquals(4, sel.getQuantity());
    }

    // ── decrement ─────────────────────────────────────────────────────────────

    @Test
    void givenAboveOne_whenDecrement_quantityDecreases() {
        var sel = new VkQuantitySelector(5, 9000, null);
        sel.increment(); // qty = 2
        sel.decrement(); // qty = 1
        assertEquals(1, sel.getQuantity());
    }

    @Test
    void givenAtOne_whenDecrement_quantityStaysAtOne() {
        var sel = new VkQuantitySelector(5, 9000, null); // starts at 1
        sel.decrement();
        assertEquals(1, sel.getQuantity());
    }

    // ── getSelection quantity value ───────────────────────────────────────────

    @Test
    void givenQuantityThree_getSelectionHasQuantityThree() {
        var sel = new VkQuantitySelector(5, 9000, null);
        sel.increment();
        sel.increment();
        assertEquals(3, sel.getSelection().getQuantity());
    }

    // ── callbacks ─────────────────────────────────────────────────────────────

    @Test
    void givenCallback_whenIncrement_callbackFires() {
        var count = new AtomicInteger(0);
        var sel = new VkQuantitySelector(5, 9000, count::incrementAndGet);
        sel.increment();
        assertEquals(1, count.get());
    }

    @Test
    void givenCallback_whenDecrement_callbackFires() {
        var count = new AtomicInteger(0);
        var sel = new VkQuantitySelector(5, 9000, count::incrementAndGet);
        sel.increment(); // count = 1
        count.set(0);
        sel.decrement();
        assertEquals(1, count.get());
    }

    @Test
    void givenCallback_atMax_incrementDoesNotFireCallback() {
        var count = new AtomicInteger(0);
        var sel = new VkQuantitySelector(1, 9000, count::incrementAndGet); // max = 1 = current
        sel.increment();
        assertEquals(0, count.get());
    }

    @Test
    void givenCallback_atOne_decrementDoesNotFireCallback() {
        var count = new AtomicInteger(0);
        var sel = new VkQuantitySelector(5, 9000, count::incrementAndGet);
        sel.decrement(); // already at 1 — no-op
        assertEquals(0, count.get());
    }

    @Test
    void givenNullCallback_noNullPointerOnIncrement() {
        var sel = new VkQuantitySelector(5, 9000, null);
        assertDoesNotThrow(sel::increment);
    }

    @Test
    void givenNullCallback_noNullPointerOnDecrement() {
        var sel = new VkQuantitySelector(5, 9000, null);
        sel.increment();
        assertDoesNotThrow(sel::decrement);
    }
}
