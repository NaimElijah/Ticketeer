package com.ticketing.system.ui.components.venue;

import com.ticketing.system.ui.components.kit.LkIcon;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;

/**
 * Composite AND/OR policy group — colored bracket with operator pill,
 * description, body slot for child rules/groups, and inline
 * "+ Add rule / + Add group" affordances. Ports the React
 * {@code PolicyGroup}.
 */
public class PolicyGroup extends Div {

    private final Div body = new Div();
    private final Div addRow = new Div();
    private final boolean isAnd;

    public PolicyGroup(String op, boolean root) {
        this.isAnd = "AND".equalsIgnoreCase(op);
        addClassName("pe-group");
        addClassName("pe-group-" + (isAnd ? "and" : "or"));
        if (root) addClassName("pe-group-root");

        // Head: operator pill + description
        Div head = new Div();
        head.addClassName("pe-group-head");
        NativeButton opBtn = new NativeButton();
        opBtn.addClassName("pe-op");
        opBtn.addClassName(isAnd ? "and" : "or");
        opBtn.add(new Span(op.toUpperCase()));
        Span caret = new Span(" ▾");
        caret.addClassName("pe-op-caret");
        opBtn.add(caret);
        Span desc = new Span(isAnd ? "All of these must be true" : "At least one of these must be true");
        desc.addClassName("pe-group-desc");
        head.add(opBtn, desc);
        super.add(head);

        body.addClassName("pe-group-body");
        super.add(body);

        // Add affordances sit at the bottom inside the body
        addRow.addClassName("pe-group-add");
        addRow.add(addBtn("+ Add rule"));
        addRow.add(addBtn("+ Add group"));
        body.add(addRow);
    }

    private NativeButton addBtn(String label) {
        NativeButton b = new NativeButton();
        b.addClassName("pe-addbtn");
        b.add(new LkIcon("plus", 15));
        b.add(new Span(" " + label.replaceFirst("\\+ ", "")));
        return b;
    }

    /** Adds child rules / nested groups to the body, before the +Add row. */
    @Override
    public void add(Component... components) {
        for (Component c : components) {
            if (c == body || c == addRow) {
                super.add(c);
            } else {
                int insertAt = body.getElement().indexOfChild(addRow.getElement());
                if (insertAt < 0) body.add(c);
                else body.getElement().insertChild(insertAt, c.getElement());
            }
        }
    }
}
