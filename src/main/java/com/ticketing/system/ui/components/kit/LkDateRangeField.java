package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;

import java.util.List;
import java.util.function.Consumer;

/**
 * Date-range field — popover with preset list + custom range stub.
 * Ports the React {@code DateRangeField}. Click a preset to fire the
 * {@link #onChange(Consumer)} callback with the new value.
 *
 * <p>The trigger is a native button named "label: value" that says it opens a
 * popup, and each preset is a radio that says whether it is chosen (#188; WCAG
 * 2.1.1, 4.1.2; {@code PopoverTriggerA11yTest}). A pick closes the list and returns
 * focus to the trigger ({@code FocusAfterRebuildTest}). The field is a {@code div}, not a
 * {@code label}, for the reason {@link LkSelect} gives.
 */
public class LkDateRangeField extends Div {

    private static final List<String> PRESETS = List.of(
        "Any time", "Today", "This weekend", "Next 7 days", "Next 30 days", "This month", "Next 3 months",
        "Custom range"
    );
    // Names the popover until label() names the field.
    private static final String UNLABELLED = "Date range";

    private final Span labelSpan = new Span();
    private final Span valueSpan = new Span();
    private final LkMenu menu = new LkMenu();
    private final NativeButton trigger = new NativeButton();
    private final LkPopover popover;
    private boolean hasLabel;
    private String label = "";
    private String currentValue;
    private Consumer<String> onChange;

    public LkDateRangeField() { this("Any time"); }

    public LkDateRangeField(String initial) {
        addClassName("lk-field");
        labelSpan.addClassName("lk-label");
        valueSpan.addClassName("lk-input-val");

        trigger.addClassName("lk-input");
        trigger.addClassName("lk-select");
        Span affix = new Span();
        affix.addClassName("lk-input-affix");
        affix.add(new LkIcon("calendar", 16));
        trigger.add(valueSpan, affix);

        popover = new LkPopover(UNLABELLED, trigger, menu).block();
        show(initial);
        rebuild();
        add(popover);
    }

    /**
     * Sets the label shown above the field, which also names its trigger and its popover.
     *
     * @param t the label
     * @return this field, for chaining
     */
    public LkDateRangeField label(String t) {
        label = t;
        labelSpan.setText(t);
        if (!hasLabel) {
            getElement().insertChild(0, labelSpan.getElement());
            hasLabel = true;
        }
        popover.name(t);
        nameTrigger();
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
        show(value);
        rebuild();
        if (onChange != null) onChange.accept(value);
        return this;
    }

    private void show(String value) {
        currentValue = value;
        valueSpan.setText(value);
        nameTrigger();
    }

    // The trigger shows the value only, so its name carries the label too.
    private void nameTrigger() {
        trigger.getElement().setAttribute(Lk.ARIA_LABEL, Lk.labelAndValue(label, currentValue));
    }

    private void rebuild() {
        menu.removeAll();
        for (String preset : PRESETS) {
            LkMenu.Item item = new LkMenu.Item(preset).choice(preset.equals(currentValue));
            item.onClick(() -> {
                if (preset.equals(currentValue)) {
                    closeAndFocusTrigger();
                    return;
                }
                show(preset);
                rebuild();
                closeAndFocusTrigger();
                if (onChange != null) onChange.accept(preset);
            });
            menu.add(item);
        }
    }

    // As LkSelect does: the field takes one preset, and the rebuild has removed the one that had focus, so the
    // list closes and focus goes back to the trigger (#188, PR #253 review; WCAG 2.4.3).
    private void closeAndFocusTrigger() {
        popover.close();
        trigger.focus();
    }
}
