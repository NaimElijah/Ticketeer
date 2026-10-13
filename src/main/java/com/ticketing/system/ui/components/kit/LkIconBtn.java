package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.NativeButton;

/**
 * Square 32×32 icon-only button. Ports the React {@code IconBtn}.
 */
public class LkIconBtn extends NativeButton {

    public LkIconBtn(Component icon) {
        addClassName("lk-iconbtn");
        if (icon != null) add(icon);
    }

    public LkIconBtn(Component icon, String tooltip) {
        this(icon);
        if (tooltip != null) getElement().setAttribute("title", tooltip);
    }
}
