package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.component.html.Span;

import java.util.List;
import java.util.function.Consumer;

/**
 * Popover-backed select. Trigger looks like {@link LkField}; panel is an
 * {@link LkMenu} of options. Ports the React {@code Select} component.
 */
public class LkSelect extends NativeLabel {

    private final Span labelSpan = new Span();
    private final Span req = new Span(" *");
    private final Span valueSpan = new Span();
    private final LkMenu menu = new LkMenu();
    private final Span trigger = new Span();
    private final LkPopover popover;
    private String currentValue;
    private Consumer<String> onChange;
    private boolean hasLabel;

    private List<String> options;

    public LkSelect(String value, List<String> options) {
        this.currentValue = value;
        this.options = options;
        addClassName("lk-field");
        labelSpan.addClassName("lk-label");
        req.addClassName("lk-req");
        valueSpan.addClassName("lk-input-val");
        valueSpan.setText(value);

        trigger.addClassName("lk-input");
        trigger.addClassName("lk-select");
        trigger.add(valueSpan, new LkIcon("caret", 13));
        trigger.getElement().setAttribute("tabindex", "0");

        popover = new LkPopover(trigger, menu).block();
        rebuildOptions(options);
        add(popover);
    }

    /** Programmatically set the value (e.g. when an external control syncs). Fires onChange. */
    public LkSelect setValue(String value) {
        if (value == null || value.equals(currentValue)) return this;
        currentValue = value;
        valueSpan.setText(value);
        rebuildOptions(options);
        if (onChange != null) onChange.accept(value);
        return this;
    }

    public LkSelect label(String t) {
        labelSpan.setText(t);
        if (!hasLabel) {
            getElement().insertChild(0, labelSpan.getElement());
            hasLabel = true;
        }
        return this;
    }

    public LkSelect required() {
        if (req.getParent().isEmpty())
            labelSpan.add(req);
        return this;
    }

    public LkSelect width(String w) {
        getStyle().set("width", w);
        return this;
    }

    public LkSelect onChange(Consumer<String> handler) {
        this.onChange = handler;
        return this;
    }

    public String getValue() {
        return currentValue;
    }

    /**
     * Replace the option list and reset the displayed value to the first option.
     * Does NOT fire onChange — used for dependent selects (e.g. city driven by country).
     */
    public LkSelect setOptions(List<String> newOptions) {
        this.options = newOptions;
        this.currentValue = newOptions.isEmpty() ? "" : newOptions.get(0);
        valueSpan.setText(currentValue);
        rebuildOptions(this.options);
        return this;
    }

    /**
     * Toggle interactive state. A disabled select is visually muted, non-clickable, removed from the
     * tab order, and exposes {@code aria-disabled} so assistive tech reports it.
     */
    public LkSelect enabled(boolean enabled) {
        trigger.getStyle().set("pointer-events", enabled ? "auto" : "none");
        trigger.getStyle().set("opacity",         enabled ? "1"    : "0.45");
        if (enabled) {
            trigger.getElement().setAttribute("tabindex", "0");
            trigger.getElement().removeAttribute("aria-disabled");
        } else {
            trigger.getElement().removeAttribute("tabindex");
            trigger.getElement().setAttribute("aria-disabled", "true");
        }
        return this;
    }

    private void rebuildOptions(List<String> options) {
        menu.removeAll();
        for (String opt : options) {
            LkMenu.Item item = new LkMenu.Item(opt);
            if (opt.equals(currentValue))
                item.active();
            item.onClick(() -> {
                currentValue = opt;
                valueSpan.setText(opt);
                if (onChange != null)
                    onChange.accept(opt);
                rebuildOptions(options);
            });
            menu.add(item);
        }
    }
}
