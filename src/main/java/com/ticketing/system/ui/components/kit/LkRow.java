package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;

/**
 * Horizontal flex row. Ports the React {@code Row} primitive.
 */
public class LkRow extends Div {

    public LkRow(Component... children) {
        getStyle()
                .set("display", "flex")
                .set("flex-wrap", "wrap")
                .set("align-items", "center")
                .set("gap", "12px");
        if (children != null && children.length > 0)
            add(children);
    }

    public LkRow gap(int px) {
        getStyle().set("gap", px + "px");
        return this;
    }

    public LkRow noWrap() {
        getStyle().set("flex-wrap", "nowrap");
        return this;
    }

    public LkRow align(String value) {
        getStyle().set("align-items", value);
        return this;
    }

    public LkRow justify(String value) {
        getStyle().set("justify-content", value);
        return this;
    }
}
