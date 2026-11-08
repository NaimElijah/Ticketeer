package com.ticketing.system.ui.components.venue;

import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Span;

import java.util.Objects;
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

    // ── initial state ────────────────────────────────────────────────────────

    @Test
    void GivenPositiveAvailable_WhenConstructed_ThenInitialQuantityIsOne() {
        var sel = new VkQuantitySelector(100, 9000, null);
        assertEquals(1, sel.getQuantity());
    }

    @Test
    void GivenPositiveAvailable_WhenConstructed_ThenHasSelection() {
        var sel = new VkQuantitySelector(100, 9000, null);
        assertTrue(sel.hasSelection());
    }

    @Test
    void GivenPositiveAvailable_WhenGetSelection_ThenReturnsStandingDto() {
        var sel = new VkQuantitySelector(100, 9000, null);
        assertNotNull(sel.getSelection());
        assertTrue(sel.getSelection().isStandingSelection());
    }

    @Test
    void GivenZeroAvailable_WhenConstructed_ThenQuantityIsZero() {
        var sel = new VkQuantitySelector(0, 9000, null);
        assertEquals(0, sel.getQuantity());
    }

    @Test
    void GivenZeroAvailable_WhenConstructed_ThenHasNoSelection() {
        var sel = new VkQuantitySelector(0, 9000, null);
        assertFalse(sel.hasSelection());
    }

    @Test
    void GivenZeroAvailable_WhenGetSelection_ThenReturnsNull() {
        var sel = new VkQuantitySelector(0, 9000, null);
        assertNull(sel.getSelection());
    }

    // ── increment ─────────────────────────────────────────────────────────────

    @Test
    void GivenBelowMax_WhenIncrement_ThenQuantityIncreases() {
        var sel = new VkQuantitySelector(5, 9000, null);
        sel.increment();
        assertEquals(2, sel.getQuantity());
    }

    @Test
    void GivenAtMax_WhenIncrement_ThenQuantityStaysAtMax() {
        var sel = new VkQuantitySelector(1, 9000, null); // starts at 1, max = 1
        sel.increment();
        assertEquals(1, sel.getQuantity());
    }

    // A sold-out zone offers nothing to hold, however often Increase is pressed.
    @Test
    void GivenZeroAvailable_WhenIncrement_ThenStaysZeroWithNoSelection() {
        var count = new AtomicInteger(0);
        var sel = new VkQuantitySelector(0, 9000, count::incrementAndGet);
        sel.increment();
        assertEquals(0, sel.getQuantity());
        assertNull(sel.getSelection());
        assertEquals(0, count.get(), "nothing changed, so the callback must not fire");
    }

    @Test
    void GivenBelowMax_WhenMultipleIncrements_ThenQuantityMatchesCountPlusTheInitialOne() {
        var sel = new VkQuantitySelector(10, 9000, null);
        sel.increment();
        sel.increment();
        sel.increment();
        assertEquals(4, sel.getQuantity());
    }

    // ── decrement ─────────────────────────────────────────────────────────────

    @Test
    void GivenAboveOne_WhenDecrement_ThenQuantityDecreases() {
        var sel = new VkQuantitySelector(5, 9000, null);
        sel.increment(); // qty = 2
        sel.decrement(); // qty = 1
        assertEquals(1, sel.getQuantity());
    }

    @Test
    void GivenAtOne_WhenDecrement_ThenQuantityStaysAtOne() {
        var sel = new VkQuantitySelector(5, 9000, null); // starts at 1
        sel.decrement();
        assertEquals(1, sel.getQuantity());
    }

    // ── getSelection quantity value ───────────────────────────────────────────

    @Test
    void GivenQuantityThree_WhenGetSelection_ThenHasQuantityThree() {
        var sel = new VkQuantitySelector(5, 9000, null);
        sel.increment();
        sel.increment();
        assertEquals(3, sel.getSelection().getQuantity());
    }

    // ── callbacks ─────────────────────────────────────────────────────────────

    @Test
    void GivenCallback_WhenIncrement_ThenCallbackFires() {
        var count = new AtomicInteger(0);
        var sel = new VkQuantitySelector(5, 9000, count::incrementAndGet);
        sel.increment();
        assertEquals(1, count.get());
    }

    @Test
    void GivenCallbackAboveOne_WhenDecrement_ThenCallbackFires() {
        var count = new AtomicInteger(0);
        var sel = new VkQuantitySelector(5, 9000, count::incrementAndGet);
        sel.increment(); // count = 1
        count.set(0);
        sel.decrement();
        assertEquals(1, count.get());
    }

    @Test
    void GivenCallbackAtMax_WhenIncrement_ThenDoesNotFireCallback() {
        var count = new AtomicInteger(0);
        var sel = new VkQuantitySelector(1, 9000, count::incrementAndGet); // max = 1 = current
        sel.increment();
        assertEquals(0, count.get());
    }

    @Test
    void GivenCallbackAtOne_WhenDecrement_ThenDoesNotFireCallback() {
        var count = new AtomicInteger(0);
        var sel = new VkQuantitySelector(5, 9000, count::incrementAndGet);
        sel.decrement(); // already at 1 — no-op
        assertEquals(0, count.get());
    }

    @Test
    void GivenNullCallback_WhenIncrement_ThenDoesNotThrow() {
        var sel = new VkQuantitySelector(5, 9000, null);
        assertDoesNotThrow(sel::increment);
    }

    @Test
    void GivenNullCallbackAboveOne_WhenDecrement_ThenDoesNotThrow() {
        var sel = new VkQuantitySelector(5, 9000, null);
        sel.increment();
        assertDoesNotThrow(sel::decrement);
    }

    // ---- UI-B16: the price preview's total does not overflow (#187) ----

    // 500 tickets at $50,000.00 is 2,500,000,000 cents, past the largest int.
    @Test
    void GivenALargeQuantityTimesPrice_WhenThePreviewIsShown_ThenTheTotalIsExact() {
        var sel = new VkQuantitySelector(1_000, 5_000_000, null);
        while (sel.getQuantity() < 500) {
            sel.increment();
        }

        assertEquals("500 tickets at $50000.00 each = $25000000.00", pricePreviewOf(sel));
    }

    private static String pricePreviewOf(Component root) {
        if (root instanceof Span span && span.hasClassName("vk-qty-price")) {
            return span.getText();
        }
        return root.getChildren().map(VkQuantitySelectorTest::pricePreviewOf)
                .filter(Objects::nonNull).findFirst().orElse(null);
    }
}
