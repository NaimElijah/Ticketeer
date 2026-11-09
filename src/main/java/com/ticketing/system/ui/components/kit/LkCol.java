package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;

/**
 * Vertical flex column. Ports the React {@code Col} primitive.
 */
public class LkCol extends Div {

    /** A column of {@code children}, 12 px apart; {@code null} or none leaves it empty. */
    public LkCol(Component... children) {
        getStyle()
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("gap", "12px")
                .set("min-width", "0");
        if (children != null && children.length > 0)
            add(children);
    }

    /** Sets the space between the children, in pixels. */
    public LkCol gap(int px) {
        getStyle().set("gap", px + "px");
        return this;
    }
}
