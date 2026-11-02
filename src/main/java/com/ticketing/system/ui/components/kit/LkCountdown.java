package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.dom.Element;

/**
 * The one countdown script for a ticket hold: the cart page, the checkout and the top bar's cart chip
 * all run it. The server sends the seconds left, computed from its own clock, and the browser counts
 * them down; it never compares a server deadline with the browser's clock, which may be off
 * (#187, UI-A13, ADR-008).
 *
 * <p>Each element keeps its own timer, so a page's countdown and the top bar's run side by side, and
 * starting a countdown again on the same element replaces the running one. A countdown inside a
 * {@code .bz-timer} chip turns the chip urgent at {@link #URGENT_SECONDS} and removes it at zero.
 */
public final class LkCountdown {

    /** Seconds left at which an enclosing {@code .bz-timer} chip turns urgent. */
    public static final int URGENT_SECONDS = 180;

    private static final String SCRIPT =
        "const t = this; let s = $0; const prefix = $1; const urgentAt = $2;"
        + "if (t.__holdTimer) clearTimeout(t.__holdTimer);"
        + "const chip = t.closest('.bz-timer');"
        + "function pad(n){ return String(n).padStart(2, '0'); }"
        + "function tick(){"
        + "  if (!t.isConnected) return;"
        + "  t.textContent = prefix + pad(Math.floor(s / 60)) + ':' + pad(s % 60);"
        + "  if (chip && s <= urgentAt) chip.classList.add('urgent');"
        + "  if (s > 0) { s--; t.__holdTimer = setTimeout(tick, 1000); }"
        + "  else if (chip) chip.remove();"
        + "}"
        + "tick();";

    private LkCountdown() { }

    /**
     * Shows {@code prefix} followed by the time left as mm:ss in {@code text}, counting down once a
     * second in the browser.
     *
     * @param text        the element whose text the countdown writes
     * @param secondsLeft the seconds the hold has left, computed on the server; zero or less shows 00:00
     * @param prefix      the text before the time, such as {@code "Time left: "}; may be empty
     */
    public static void start(Element text, long secondsLeft, String prefix) {
        // Vaadin's JsonCodec cannot encode a long; a hold's seconds always fit an int.
        int seconds = (int) Math.max(0, Math.min(secondsLeft, Integer.MAX_VALUE));
        text.executeJs(SCRIPT, seconds, prefix, URGENT_SECONDS);
    }
}
