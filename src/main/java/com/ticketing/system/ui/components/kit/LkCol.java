package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;

/**
 * Vertical flex column. Ports the React {@code Col} primitive.
 */
public class LkCol extends Div {

    public LkCol(Component... children) {
        getStyle()
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("gap", "12px")
                .set("min-width", "0");
        if (children != null && children.length > 0)
            add(children);
    }

    public LkCol gap(int px) {
        getStyle().set("gap", px + "px");
        return this;
    }
}
