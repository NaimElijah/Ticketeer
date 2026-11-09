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
 * A numbered seat's tooltip ({@code title}) is its name, kept in step with each state change: a
 * different title would be read as a second description, and go stale.
 */
public class VkSeat extends NativeButton {

    /**
     * A seat's state, each drawn in its own colour and said in words in the seat's name.
     *
     * <ul>
     *   <li>{@code free}: nobody holds it; the buyer may pick it.</li>
     *   <li>{@code mine}: the buyer's choice, picked on the page they are on.</li>
     *   <li>{@code held}: reserved by a buyer and not yet paid for. The seat data does not say by whom,
     *       so it may be the buyer's own reservation, and its name says only "reserved".</li>
     *   <li>{@code sold}: paid for.</li>
     * </ul>
     */
    public enum State {
        free("available"), mine("selected"), held("reserved"), sold("sold");

        private final String spoken;

        State(String spoken) {
            this.spoken = spoken;
        }
    }

    private static final String ARIA_LABEL = "aria-label";
    private static final String TITLE = "title";
    private static final String ARIA_PRESSED = "aria-pressed";
    private static final String ARIA_HIDDEN = "aria-hidden";
    private static final String TABINDEX = "tabindex";
    private static final String NOT_A_TAB_STOP = "-1";
    private static final String SEAT = "Seat";

    private final String num;

    /**
     * @param state the seat's initial state
     * @param num   the seat number shown on the tile and in its accessible name; {@code null} shows no
     *              number and names the seat "Seat"
     */
    public VkSeat(State state, String num) {
        this.num = num;
        addClassName("vk-seat");
        if (num != null) {
            setText(num);
        }
        setState(state);
    }

    /**
     * Swaps the state CSS class, the state a screen reader hears and the tooltip, in place — avoids
     * recreating the DOM node on toggle.
     */
    public void setState(State state) {
        for (State s : State.values()) removeClassName("vk-seat-" + s.name());
        addClassName("vk-seat-" + state.name());
        String name = spokenName(state);
        getElement().setAttribute(ARIA_LABEL, name);
        if (num != null) {
            getElement().setAttribute(TITLE, name);
        }
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
