package com.ticketing.system.ui.components.venue;

import com.vaadin.flow.component.html.NativeButton;

/**
 * Single seat tile — green/amber/hatched/red based on locking state.
 * Ports the React {@code Seat} component.
 *
 * <p>A screen reader hears the seat's number and state ("Seat 7, available"), not only its
 * colour; a free or selected seat is a toggle ({@code aria-pressed}), and a held or sold seat
 * is disabled, so a keyboard user does not tab through every seat nobody can pick, while its
 * name still says why when read (#188; WCAG 1.4.1, 2.4.3, 4.1.2; {@code SeatAndZoneStateTest}).
 */
public class VkSeat extends NativeButton {

    public enum State {
        free("available"), mine("selected"), held("held by another buyer"), sold("sold");

        private final String spoken;

        State(String spoken) {
            this.spoken = spoken;
        }
    }

    private static final String ARIA_LABEL = "aria-label";
    private static final String ARIA_PRESSED = "aria-pressed";
    private static final String ARIA_HIDDEN = "aria-hidden";
    private static final String TABINDEX = "tabindex";
    private static final String NOT_A_TAB_STOP = "-1";
    private static final String SEAT = "Seat";

    private final String num;

    public VkSeat(State state, String num) {
        this.num = num;
        addClassName("vk-seat");
        if (num != null) {
            setText(num);
            getElement().setAttribute("title", "Seat " + num + " · " + state.name());
        }
        setState(state);
    }

    /** Swaps the state CSS class and the state a screen reader hears, in place — avoids recreating the DOM node on toggle. */
    public void setState(State state) {
        for (State s : State.values()) removeClassName("vk-seat-" + s.name());
        addClassName("vk-seat-" + state.name());
        getElement().setAttribute(ARIA_LABEL, spokenName(state));
        boolean pickable = state == State.free || state == State.mine;
        setEnabled(pickable);
        if (pickable) {
            getElement().setAttribute(ARIA_PRESSED, String.valueOf(state == State.mine));
        } else {
            getElement().removeAttribute(ARIA_PRESSED);
        }
    }

    /**
     * Marks the seat as a picture nobody can pick, such as in a colour key or a preview: out of
     * the Tab order and hidden from screen readers, which the surrounding text describes instead.
     *
     * @return this seat, for chaining
     */
    public VkSeat decorative() {
        getElement().setAttribute(TABINDEX, NOT_A_TAB_STOP);
        getElement().setAttribute(ARIA_HIDDEN, "true");
        return this;
    }

    private String spokenName(State state) {
        String seat = num == null || num.isBlank() ? SEAT : SEAT + " " + num;
        return seat + ", " + state.spoken;
    }
}
