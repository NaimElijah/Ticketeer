package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.component.html.Span;

/**
 * Labeled mock input — visual placeholder that matches the React
 * {@code Field} component. Used by placeholder views that want the
 * design without the form behavior (real Vaadin {@code TextField} is
 * used on screens that actually submit).
 */
public class LkField extends NativeLabel {

    private final Span labelSpan = new Span();
    private final Span req = new Span(" *");
    private final Span inputSpan = new Span();
    private final Span prefixAffix = new Span();
    private final Span valueSpan = new Span();
    private final Span suffixAffix = new Span();
    private Span helperSpan;
    private boolean hasLabel;
    private boolean hasPrefix;
    private boolean hasSuffix;
    private boolean invalid;

    public LkField() {
        addClassName("lk-field");
        labelSpan.addClassName("lk-label");
        req.addClassName("lk-req");
        inputSpan.addClassName("lk-input");
        prefixAffix.addClassName("lk-input-affix");
        valueSpan.addClassName("lk-input-val");
        suffixAffix.addClassName("lk-input-affix");
        inputSpan.add(valueSpan);
        add(inputSpan);
    }

    public LkField label(String t) {
        labelSpan.setText(t);
        if (!hasLabel) {
            getElement().insertChild(0, labelSpan.getElement());
            hasLabel = true;
        }
        return this;
    }

    public LkField required() {
        if (req.getParent().isEmpty())
            labelSpan.add(req);
        return this;
    }

    public LkField placeholder(String p) {
        if (valueSpan.getText() == null || valueSpan.getText().isEmpty()) {
            valueSpan.setText(p);
            valueSpan.getStyle().set("color", "var(--faint)");
        }
        return this;
    }

    public LkField value(String v) {
        valueSpan.setText(v == null ? "" : v);
        valueSpan.getStyle().set("color", "var(--text)");
        return this;
    }

    public LkField prefix(Component c) {
        prefixAffix.removeAll();
        if (c != null)
            prefixAffix.add(c);
        if (!hasPrefix) {
            inputSpan.getElement().insertChild(0, prefixAffix.getElement());
            hasPrefix = true;
        }
        return this;
    }

    public LkField prefix(String text) {
        prefixAffix.setText(text);
        if (!hasPrefix) {
            inputSpan.getElement().insertChild(0, prefixAffix.getElement());
            hasPrefix = true;
        }
        return this;
    }

    public LkField suffix(Component c) {
        suffixAffix.removeAll();
        if (c != null)
            suffixAffix.add(c);
        if (!hasSuffix) {
            inputSpan.add(suffixAffix);
            hasSuffix = true;
        }
        return this;
    }

    public LkField helper(String text) {
        if (helperSpan == null) {
            helperSpan = new Span();
            helperSpan.addClassName("lk-helper");
            add(helperSpan);
        }
        helperSpan.setText(text);
        if (invalid)
            helperSpan.addClassName("lk-helper-err");
        return this;
    }

    public LkField invalid() {
        invalid = true;
        inputSpan.addClassName("lk-input-invalid");
        if (helperSpan != null)
            helperSpan.addClassName("lk-helper-err");
        return this;
    }

    public LkField width(String w) {
        getStyle().set("width", w);
        return this;
    }
}
