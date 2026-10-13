package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Filter chip with a check-row popover and Apply button. Ports the
 * React {@code FilterChip}. Multi-select by default; pass
 * {@code single} for single-select.
 */
public class LkFilterChip extends Div {

    private final String label;
    private final List<String> options;
    private final boolean single;
    private final Set<String> selected;
    private final Span chip = new Span();
    private final LkMenu menu = new LkMenu();
    private LkMenu.Label headerLabel;
    private Runnable onApply = () -> { };

    public LkFilterChip(String label, List<String> options) {
        this(label, options, false, List.of());
    }

    public LkFilterChip(String label, List<String> options, boolean single, List<String> applied) {
        this.label = label;
        this.options = options;
        this.single = single;
        this.selected = new HashSet<>(applied);

        getStyle().set("display", "inline-flex");
        chip.addClassName("lk-chip");
        chip.getElement().setAttribute("tabindex", "0");
        rebuildChip();

        rebuildMenu();

        add(new LkPopover(chip, menu));
    }

    private void rebuildChip() {
        chip.removeAll();
        chip.setText(label + (selected.isEmpty() ? "" : " · " + selected.size()));
        Span caret = new Span("▾");
        caret.addClassName("lk-chip-caret");
        chip.add(caret);
        if (selected.isEmpty()) chip.removeClassName("on");
        else chip.addClassName("on");
    }

    private void rebuildMenu() {
        menu.removeAll();
        headerLabel = new LkMenu.Label(label);
        if (!selected.isEmpty()) headerLabel.action("Clear", () -> {
            selected.clear();
            rebuildChip();
            rebuildMenu();
        });
        menu.add(headerLabel);

        for (String opt : options) {
            boolean on = selected.contains(opt);
            NativeButton row = new NativeButton();
            row.addClassName("lk-check-row");
            Span box = new Span();
            box.addClassName("lk-checkbox");
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
            });
            menu.add(row);
        }

        menu.add(new LkMenu.Divider());

        NativeButton apply = new NativeButton("Apply");
        apply.addClassName("lk-menu-apply");
        apply.addClickListener(e -> onApply.run());
        menu.add(apply);
    }

    public Set<String> getSelected() { return selected; }

    /** Run a callback when the chip's Apply button is pressed (e.g. re-query). */
    public LkFilterChip onApply(Runnable callback) {
        this.onApply = callback == null ? () -> { } : callback;
        return this;
    }
}
