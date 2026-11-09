package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filter chip with a check-row popover and Apply button. Ports the
 * React {@code FilterChip}. Multi-select by default; pass
 * {@code single} for single-select.
 *
 * <p>The chip is a native button named "label: choices" that says it opens a popup,
 * and each option says whether it is ticked: a checkbox, or a radio when
 * {@code single} (#188; WCAG 2.1.1, 4.1.2; {@code PopoverTriggerA11yTest}).
 */
public class LkFilterChip extends Div {

    // The chip's name while nothing is ticked, which filters nothing out.
    private static final String NOTHING_TICKED = "any";
    private static final String CHOICE_SEPARATOR = ", ";

    private final String label;
    private final List<String> options;
    private final boolean single;
    private final Set<String> selected;
    private final NativeButton chip = new NativeButton();
    private final LkMenu menu = new LkMenu();
    /** Each option's row, by option, as the last menu rebuild made them. */
    private final Map<String, NativeButton> optionRows = new HashMap<>();
    private LkMenu.Label headerLabel;
    private Runnable onApply = () -> { };

    /** A multi-select chip with nothing ticked. */
    public LkFilterChip(String label, List<String> options) {
        this(label, options, false, List.of());
    }

    /**
     * @param label   the chip's text and the popup's heading; the chip appends the number ticked
     * @param options the choices, in the order shown
     * @param single  whether at most one option can be ticked
     * @param applied the options ticked at the start; not {@code null}
     */
    public LkFilterChip(String label, List<String> options, boolean single, List<String> applied) {
        this.label = label;
        this.options = options;
        this.single = single;
        this.selected = new HashSet<>(applied);

        getStyle().set("display", "inline-flex");
        chip.addClassName("lk-chip");
        rebuildChip();

        rebuildMenu();

        add(new LkPopover(label, chip, menu));
    }

    private void rebuildChip() {
        chip.removeAll();
        chip.setText(label + (selected.isEmpty() ? "" : " · " + selected.size()));
        Span caret = Lk.decoration("▾");
        caret.addClassName("lk-chip-caret");
        chip.add(caret);
        if (selected.isEmpty()) chip.removeClassName("on");
        else chip.addClassName("on");
        // The chip shows how many options are ticked; its name says which.
        String ticked = options.stream()
            .filter(selected::contains)
            .collect(Collectors.joining(CHOICE_SEPARATOR));
        chip.getElement().setAttribute(Lk.ARIA_LABEL,
            Lk.labelAndValue(label, ticked.isEmpty() ? NOTHING_TICKED : ticked));
    }

    private void rebuildMenu() {
        menu.removeAll();
        optionRows.clear();
        headerLabel = new LkMenu.Label(label);
        if (!selected.isEmpty()) headerLabel.action("Clear", () -> {
            selected.clear();
            rebuildChip();
            rebuildMenu();
            // With nothing ticked the rebuilt menu has no Clear; focus its first option (#188, WCAG 2.4.3).
            options.stream().findFirst().map(optionRows::get).ifPresent(NativeButton::focus);
        });
        menu.add(headerLabel);

        for (String opt : options) {
            boolean on = selected.contains(opt);
            NativeButton row = new NativeButton();
            row.addClassName("lk-check-row");
            row.getElement().setAttribute(Lk.ROLE, single ? Lk.ROLE_RADIO : Lk.ROLE_CHECKBOX);
            row.getElement().setAttribute(Lk.ARIA_CHECKED, String.valueOf(on));
            Span box = new Span();
            box.addClassName("lk-checkbox");
            box.getElement().setAttribute(Lk.ARIA_HIDDEN, "true");
            if (!single) {
                // square checkbox
            } else {
                box.addClassName("rnd");
            }
            if (on) {
                box.addClassName("on");
                box.add(new LkIcon("check", 11, 2.6));
            }
            Span optLabel = new Span(opt);
            row.add(box, optLabel);
            row.addClickListener(e -> {
                if (single) {
                    if (selected.contains(opt)) selected.clear();
                    else { selected.clear(); selected.add(opt); }
                } else {
                    if (selected.contains(opt)) selected.remove(opt);
                    else selected.add(opt);
                }
                rebuildChip();
                rebuildMenu();
                // The rebuild removed the option that had focus; focus its replacement (#188, WCAG 2.4.3).
                optionRows.get(opt).focus();
            });
            optionRows.put(opt, row);
            menu.add(row);
        }

        menu.add(new LkMenu.Divider());

        NativeButton apply = new NativeButton("Apply");
        apply.addClassName("lk-menu-apply");
        apply.addClickListener(e -> onApply.run());
        menu.add(apply);
    }

    /**
     * The options ticked now, which change as the user ticks them, even before Apply is pressed.
     * It is the chip's own set, not a copy.
     */
    public Set<String> getSelected() { return selected; }

    /** Run a callback when the chip's Apply button is pressed (e.g. re-query). */
    public LkFilterChip onApply(Runnable callback) {
        this.onApply = callback == null ? () -> { } : callback;
        return this;
    }
}
