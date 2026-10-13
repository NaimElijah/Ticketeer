package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.popover.Popover;
import com.vaadin.flow.component.popover.PopoverPosition;
import com.vaadin.flow.component.popover.PopoverVariant;

/**
 * Click-to-open popover — wraps Vaadin's {@link Popover}. Renders the
 * trigger inline; the panel attaches and positions itself relative to
 * the trigger via Vaadin's overlay machinery.
 *
 * <p>Use as a regular component: {@code parent.add(new LkPopover(triggerBtn, panelDiv));}
 * Both the trigger and the popover overlay are managed inside.
 */
public class LkPopover extends Div {

    private final Popover popover = new Popover();
    private final Component trigger;

    public LkPopover(Component trigger, Component... content) {
        this.trigger = trigger;
        getStyle().set("display", "inline-flex");
        add(trigger);
        popover.setTarget(trigger);
        popover.setOpenOnClick(true);
        popover.setOpenOnHover(false);
        popover.setOpenOnFocus(false);
        popover.setModal(false);
        popover.setBackdropVisible(false);
        popover.setPosition(PopoverPosition.BOTTOM_END);
        if (content != null && content.length > 0) popover.add(content);
        add(popover);
    }

    /** Replace the popover panel content. */
    public LkPopover content(Component... content) {
        popover.removeAll();
        if (content != null) popover.add(content);
        return this;
    }

    public LkPopover position(PopoverPosition p) { popover.setPosition(p); return this; }
    public LkPopover width(String w)             { popover.setWidth(w); return this; }
    public LkPopover variant(PopoverVariant v)   { popover.addThemeVariants(v); return this; }
    public LkPopover modal()                     { popover.setModal(true); popover.setBackdropVisible(true); return this; }

    /**
     * Switch the wrapper from {@code inline-flex} to {@code block} +
     * {@code width:100%} so the trigger fills its parent container.
     * Used by form fields like {@link LkSelect} and {@link LkDateRangeField}
     * that should stretch across the filter sidebar.
     */
    public LkPopover block() {
        getStyle().set("display", "block").set("width", "100%");
        return this;
    }

    public Component getTrigger() { return trigger; }
    public Popover getPopover()   { return popover; }
}
