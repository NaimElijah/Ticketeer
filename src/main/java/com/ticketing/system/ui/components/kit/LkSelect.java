package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;

import java.util.List;
import java.util.function.Consumer;

/**
 * Popover-backed select. Trigger looks like a text input (the kit's {@code .lk-input}); panel is an
 * {@link LkMenu} of options. Ports the React {@code Select} component.
 *
 * <p>The trigger is a native button named "label: value" that says it opens a
 * popup, and each option is a radio that says whether it is chosen (#188; WCAG
 * 2.1.1, 4.1.2; {@code PopoverTriggerA11yTest}). A pick closes the list and returns
 * focus to the trigger ({@code FocusAfterRebuildTest}). The field is a {@code div}, not a
 * {@code label}: a label would hand every click inside the popover to the trigger,
 * which toggles the popover.
 */
public class LkSelect extends Div {

    // Names the popover until label() names the field.
    private static final String UNLABELLED = "Options";

    private final Span labelSpan = new Span();
    private final Span req = new Span(" *");
    private final Span valueSpan = new Span();
    private final LkMenu menu = new LkMenu();
    private final NativeButton trigger = new NativeButton();
    private final LkPopover popover;
    private String label = "";
    private String currentValue;
    private Consumer<String> onChange;
    private boolean hasLabel;

    private List<String> options;

    public LkSelect(String value, List<String> options) {
        this.options = options;
        addClassName("lk-field");
        labelSpan.addClassName("lk-label");
        req.addClassName("lk-req");
        valueSpan.addClassName("lk-input-val");

        trigger.addClassName("lk-input");
        trigger.addClassName("lk-select");
        trigger.add(valueSpan, new LkIcon("caret", 13));

        popover = new LkPopover(UNLABELLED, trigger, menu).block();
        show(value);
        rebuildOptions(options);
        add(popover);
    }

    /** Programmatically set the value (e.g. when an external control syncs). Fires onChange. */
    public LkSelect setValue(String value) {
        if (value == null || value.equals(currentValue)) return this;
        show(value);
        rebuildOptions(options);
        if (onChange != null) onChange.accept(value);
        return this;
    }

    /**
     * Sets the label shown above the field, which also names its trigger and its popover.
     *
     * @param t the label
     * @return this select, for chaining
     */
    public LkSelect label(String t) {
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

    /**
     * Moves focus to the select's trigger: for a view that rebuilt the select in answer to its own change,
     * so focus is not left on the removed one (#188, WCAG 2.4.3).
     */
    public void focus() {
        trigger.focus();
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
        show(newOptions.isEmpty() ? "" : newOptions.get(0));
        rebuildOptions(this.options);
        return this;
    }

    /**
     * Toggle interactive state. A disabled select is visually muted, and its trigger is a
     * disabled button: out of the tab order, and reported unavailable to assistive tech.
     */
    public LkSelect enabled(boolean enabled) {
        trigger.getStyle().set("pointer-events", enabled ? "auto" : "none");
        trigger.getStyle().set("opacity",         enabled ? "1"    : "0.45");
        trigger.setEnabled(enabled);
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

    private void rebuildOptions(List<String> options) {
        menu.removeAll();
        for (String opt : options) {
            LkMenu.Item item = new LkMenu.Item(opt).choice(opt.equals(currentValue));
            item.onClick(() -> {
                show(opt);
                rebuildOptions(options);
                closeAndFocusTrigger();
                if (onChange != null)
                    onChange.accept(opt);
            });
            menu.add(item);
        }
    }

    // A select takes one choice, and the rebuild has removed the option that had focus, so the list closes
    // and focus goes back to the trigger (#188, PR #253 review; WCAG 2.4.3). It runs before onChange, so a
    // view that rebuilds and refocuses the select in answer has the last word.
    private void closeAndFocusTrigger() {
        popover.close();
        trigger.focus();
    }
}
