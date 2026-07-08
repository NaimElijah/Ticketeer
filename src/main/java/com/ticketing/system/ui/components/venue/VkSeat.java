package com.ticketing.system.ui.components.venue;

import com.vaadin.flow.component.html.NativeButton;

/**
 * Single seat tile — green/amber/hatched/red based on locking state.
 * Ports the React {@code Seat} component.
 */
public class VkSeat extends NativeButton {

    public enum State { free, mine, held, sold }

    public VkSeat(State state, String num) {
        addClassName("vk-seat");
        addClassName("vk-seat-" + state.name());
        if (num != null) {
            setText(num);
            getElement().setAttribute("title", "Seat " + num + " · " + state.name());
        }
    }

    /** Swaps the state CSS class in place — avoids recreating the DOM node on toggle. */
    public void setState(State state) {
        for (State s : State.values()) removeClassName("vk-seat-" + s.name());
        addClassName("vk-seat-" + state.name());
    }
}
