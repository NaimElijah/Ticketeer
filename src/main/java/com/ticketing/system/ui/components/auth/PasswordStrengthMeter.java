package com.ticketing.system.ui.components.auth;

import com.ticketing.system.ui.presenters.auth.PasswordStrength;
import com.ticketing.system.ui.presenters.auth.PasswordStrength.Rule;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.progressbar.ProgressBarVariant;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Renders a {@link PasswordStrength} tier as a Vaadin {@link ProgressBar}
 * plus a row of rule chips ({@link VaadinIcon#CHECK_CIRCLE} when met,
 * {@link VaadinIcon#CIRCLE_THIN} when missing).
 *
 * <p>Call {@link #update(String)} on every value-change of the password
 * field. Empty input renders an invisible meter so the form layout
 * doesn't jump when the user starts typing.
 */
public class PasswordStrengthMeter extends Div {

    private static final Map<Rule, String> RULE_LABELS = new LinkedHashMap<>();
    static {
        RULE_LABELS.put(Rule.MIN_8_CHARS,   "8+ chars");
        RULE_LABELS.put(Rule.HAS_LETTER,    "letter");
        RULE_LABELS.put(Rule.HAS_DIGIT,     "digit");
        RULE_LABELS.put(Rule.MIN_12_CHARS,  "12+ chars");
        RULE_LABELS.put(Rule.HAS_UPPERCASE, "uppercase");
        RULE_LABELS.put(Rule.HAS_LOWERCASE, "lowercase");
        RULE_LABELS.put(Rule.HAS_SPECIAL,   "symbol");
    }

    private final ProgressBar bar = new ProgressBar(0, 1, 0);
    private final Span label = new Span();
    private final Div ruleRow = new Div();

    public PasswordStrengthMeter() {
        addClassName("pw-strength-meter");
        getStyle().set("display", "flex").set("flex-direction", "column")
            .set("gap", "6px").set("margin-top", "4px");

        bar.setWidthFull();
        bar.getStyle().set("height", "4px");
        label.getStyle().set("font-size", "12.5px").set("font-weight", "600");

        Div barRow = new Div(bar, label);
        barRow.getStyle().set("display", "flex").set("align-items", "center")
            .set("gap", "10px");

        ruleRow.getStyle().set("display", "flex").set("flex-wrap", "wrap")
            .set("gap", "6px 12px").set("font-size", "12px")
            .set("color", "var(--lumo-secondary-text-color)");

        add(barRow, ruleRow);
        update("");
    }

    public void update(String password) {
        PasswordStrength tier = PasswordStrength.of(password);
        bar.setValue(tier.progressValue());
        // Lumo error/success/contrast variants give us the colored fill for free.
        bar.removeThemeVariants(
            ProgressBarVariant.LUMO_ERROR,
            ProgressBarVariant.LUMO_SUCCESS,
            ProgressBarVariant.LUMO_CONTRAST);
        switch (tier) {
            case NONE -> bar.addThemeVariants(ProgressBarVariant.LUMO_CONTRAST);
            case WEAK -> bar.addThemeVariants(ProgressBarVariant.LUMO_ERROR);
            case FAIR -> { /* default amber from Lumo */ }
            case STRONG -> bar.addThemeVariants(ProgressBarVariant.LUMO_SUCCESS);
        }
        label.setText(tier.label());
        label.getStyle().set("color", tier.cssColorVar());

        Set<Rule> met = PasswordStrength.rulesMet(password);
        ruleRow.removeAll();
        for (Map.Entry<Rule, String> e : RULE_LABELS.entrySet()) {
            ruleRow.add(chip(e.getValue(), met.contains(e.getKey())));
        }
    }

    private static Span chip(String text, boolean met) {
        Icon icon = (met ? VaadinIcon.CHECK_CIRCLE : VaadinIcon.CIRCLE_THIN).create();
        icon.getStyle()
            .set("width", "12px").set("height", "12px")
            .set("color", met ? "var(--lumo-success-color)" : "var(--lumo-contrast-30pct)");
        Span chip = new Span(icon, new Span(" " + text));
        chip.getStyle().set("display", "inline-flex").set("align-items", "center")
            .set("gap", "2px");
        if (met) {
            chip.getStyle().set("color", "var(--lumo-body-text-color)");
        }
        return chip;
    }
}
