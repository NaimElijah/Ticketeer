package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;

/**
 * A "✕" control that removes one item from a list: a native button named "Remove &lt;item&gt;",
 * so a keyboard reaches it and a screen reader says what it removes, with a target of at
 * least 24 by 24 pixels from the kit's {@code .lk-remove} class (#188; WCAG 2.1.1, 2.5.8,
 * 4.1.2; {@code ClickOnlyControlsTest}). The glyph is decoration, hidden from screen readers.
 *
 * <p>A click on it does not reach an element around it, so it can sit inside a row that is
 * itself clickable, such as the venue editor's zone rows.
 */
public class LkRemoveButton extends NativeButton {

    private static final String CROSS = "✕";
    private static final String NAME_PREFIX = "Remove ";
    private static final String CLICK = "click";
    private static final String STOP_PROPAGATION = "event.stopPropagation()";

    /**
     * A remove button showing a "✕".
     *
     * @param item     what the button removes, as the user knows it; it completes the name "Remove …"
     * @param onRemove runs when the button is activated
     */
    public LkRemoveButton(String item, Runnable onRemove) {
        this(item, new Span(CROSS), onRemove);
    }

    /**
     * A remove button showing {@code glyph}, such as an icon.
     *
     * @param item     what the button removes, as the user knows it; it completes the name "Remove …"
     * @param glyph    what the button shows; hidden from screen readers
     * @param onRemove runs when the button is activated
     */
    public LkRemoveButton(String item, Component glyph, Runnable onRemove) {
        addClassName("lk-remove");
        getElement().setAttribute(Lk.ARIA_LABEL, NAME_PREFIX + item);
        glyph.getElement().setAttribute(Lk.ARIA_HIDDEN, "true");
        add(glyph);
        getElement().addEventListener(CLICK, e -> onRemove.run()).addEventData(STOP_PROPAGATION);
    }
}
