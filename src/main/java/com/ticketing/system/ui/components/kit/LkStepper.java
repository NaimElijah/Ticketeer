package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.component.html.Span;

/**
 * Quantity stepper — − / value / +. Ports the React {@code Stepper}.
 * Visual only; mutating handlers are out of scope for the kit.
 */
public class LkStepper extends NativeLabel {

    private final Span labelSpan = new Span();
    private final Span stepperBox = new Span();
    private final Span value = new Span();
    private boolean hasLabel;

    public LkStepper(String value) {
        addClassName("lk-field");
        labelSpan.addClassName("lk-label");
        stepperBox.addClassName("lk-stepper");

        Span minus = new Span("−"); minus.addClassName("lk-step-btn");
        Span plus  = new Span("+"); plus.addClassName("lk-step-btn");
        this.value.addClassName("lk-step-val");
        this.value.setText(value);

        stepperBox.add(minus, this.value, plus);
        add(stepperBox);
    }

    public LkStepper label(String t) {
        labelSpan.setText(t);
        if (!hasLabel) {
            getElement().insertChild(0, labelSpan.getElement());
            hasLabel = true;
        }
        return this;
    }

    public LkStepper width(String w) {
        getStyle().set("width", w);
        return this;
    }
}
