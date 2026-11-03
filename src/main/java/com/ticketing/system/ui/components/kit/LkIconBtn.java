package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.NativeButton;

/**
 * Square 32×32 icon-only button. Ports the React {@code IconBtn}.
 *
 * <p>The tooltip also names the button ({@code aria-label}), since a title alone is not read by every
 * screen reader; a button repeated on each row adds the row with {@link #forRow} (#188, WCAG 4.1.2, 2.4.6).
 */
public class LkIconBtn extends NativeButton {

    private static final String TITLE = "title";

    private String tooltip;

    public LkIconBtn(Component icon) {
        addClassName("lk-iconbtn");
        if (icon != null) add(icon);
    }

    public LkIconBtn(Component icon, String tooltip) {
        this(icon);
        if (tooltip != null) {
            this.tooltip = tooltip;
            getElement().setAttribute(TITLE, tooltip);
            getElement().setAttribute(Lk.ARIA_LABEL, tooltip);
        }
    }

    /**
     * Names the button for the row it acts on, "Revoke: carol" ({@link Lk#actionFor}); the tooltip stays the
     * bare action.
     *
     * @param row what names the row
     * @return this button, for chaining
     * @throws IllegalStateException if the button has no tooltip to name the action
     */
    public LkIconBtn forRow(String row) {
        if (tooltip == null) {
            throw new IllegalStateException("an icon button needs a tooltip to be named for its row");
        }
        getElement().setAttribute(Lk.ARIA_LABEL, Lk.actionFor(tooltip, row));
        return this;
    }
}
