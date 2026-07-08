package com.ticketing.system.ui.components.venue;

import com.ticketing.system.ui.components.kit.LkIcon;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;

/**
 * Leaf policy rule — single inline row with drag handle, rule-type
 * select, operator pill, value (+ optional unit), trailing × remove
 * button. Ports the React {@code PolicyRule}.
 */
public class PolicyRule extends Div {

    public PolicyRule(String type, String cmp, String value, String unit) {
        addClassName("pe-rule");

        Span handle = new Span("⋮⋮");
        handle.addClassName("pe-rule-handle");

        NativeButton typeBtn = new NativeButton();
        typeBtn.addClassName("pe-rule-type");
        typeBtn.addClassName("lk-mono");
        typeBtn.add(new Span(type + " "));
        Span typeCaret = new Span("▾");
        typeCaret.addClassName("pe-rule-caret");
        typeBtn.add(typeCaret);

        Span op = new Span(cmp);
        op.addClassName("pe-rule-op");

        Span val = new Span();
        val.addClassName("pe-rule-val");
        val.add(new Span(value));
        if (unit != null && !unit.isEmpty()) {
            Span u = new Span();
            u.getElement().setProperty("innerHTML", "<em>" + escape(unit) + "</em>");
            val.add(u);
        }

        Span spacer = new Span();
        spacer.addClassName("pe-rule-spacer");

        NativeButton remove = new NativeButton();
        remove.addClassName("pe-rule-x");
        remove.getElement().setAttribute("aria-label", "Remove rule");
        remove.add(new LkIcon("close", 15));

        add(handle, typeBtn, op, val, spacer, remove);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
