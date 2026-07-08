package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.component.html.Span;

import java.util.List;
import java.util.function.Consumer;

/**
 * Date-range field — popover with preset list + custom range stub.
 * Ports the React {@code DateRangeField}. Click a preset to fire the
 * {@link #onChange(Consumer)} callback with the new value.
 */
public class LkDateRangeField extends NativeLabel {

    private static final List<String> PRESETS = List.of(
        "Any time", "Today", "This weekend", "Next 7 days", "Next 30 days", "This month", "Next 3 months",
        "Custom range"
    );

    private final Span labelSpan = new Span();
    private final Span valueSpan = new Span();
    private final LkMenu menu = new LkMenu();
    private boolean hasLabel;
    private String currentValue;
    private Consumer<String> onChange;

    public LkDateRangeField() { this("Any time"); }

    public LkDateRangeField(String initial) {
        this.currentValue = initial;
        addClassName("lk-field");
        labelSpan.addClassName("lk-label");
        valueSpan.addClassName("lk-input-val");
        valueSpan.setText(initial);

        Span trigger = new Span();
        trigger.addClassName("lk-input");
        trigger.addClassName("lk-select");
        Span affix = new Span();
        affix.addClassName("lk-input-affix");
        affix.add(new LkIcon("calendar", 16));
        trigger.add(valueSpan, affix);
        trigger.getElement().setAttribute("tabindex", "0");

        rebuild();
        add(new LkPopover(trigger, menu).block());
    }

    public LkDateRangeField label(String t) {
        labelSpan.setText(t);
        if (!hasLabel) {
            getElement().insertChild(0, labelSpan.getElement());
            hasLabel = true;
        }
        return this;
    }

    public LkDateRangeField width(String w) { getStyle().set("width", w); return this; }

    public LkDateRangeField onChange(Consumer<String> handler) {
        this.onChange = handler;
        return this;
    }

    public String getValue() { return currentValue; }

    /** Programmatically set the value (e.g. from a "Clear all" reset). Fires onChange. */
    public LkDateRangeField setValue(String value) {
        if (value == null || value.equals(currentValue)) return this;
        currentValue = value;
        valueSpan.setText(value);
        rebuild();
        if (onChange != null) onChange.accept(value);
        return this;
    }

    private void rebuild() {
        menu.removeAll();
        for (String preset : PRESETS) {
            LkMenu.Item item = new LkMenu.Item(preset);
            if (preset.equals(currentValue)) item.active();
            item.onClick(() -> {
                if (preset.equals(currentValue)) return;
                currentValue = preset;
                valueSpan.setText(preset);
                rebuild();
                if (onChange != null) onChange.accept(preset);
            });
            menu.add(item);
        }
    }
}
