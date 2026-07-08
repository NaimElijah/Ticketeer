package com.ticketing.system.Presentation.components.messaging;
import com.ticketing.system.identity.domain.Admin;

import com.ticketing.system.Presentation.components.kit.LkIcon;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;

/**
 * Conversation list row used by master/detail inboxes (Support inbox,
 * Owner inquiries, Admin complaint queue). Ports {@code .md-conv} from
 * the React kit.
 */
public class MdConvRow extends NativeButton {

    private boolean active;

    public MdConvRow(String iconName, String subject, String who, String time, String unread) {
        addClassName("md-conv");

        Span ic = new Span();
        ic.addClassName("md-conv-ic");
        ic.add(new LkIcon(iconName, 17));

        Div body = new Div();
        body.addClassName("md-conv-body");
        Div top = new Div();
        top.addClassName("md-conv-top");
        Span subj = new Span(subject);
        subj.addClassName("md-conv-subj");
        top.add(subj);
        if (unread != null && !unread.isEmpty()) {
            Span dot = new Span(unread);
            dot.addClassName("md-conv-dot");
            top.add(dot);
        }
        Span whoSpan = new Span(who);
        whoSpan.addClassName("md-conv-who");
        body.add(top, whoSpan);

        Span timeSpan = new Span(time);
        timeSpan.addClassName("md-conv-time");

        add(ic, body, timeSpan);
    }

    public MdConvRow active() { return active(true); }

    public MdConvRow active(boolean on) {
        if (on && !active) { addClassName("on"); active = true; }
        else if (!on && active) { removeClassName("on"); active = false; }
        return this;
    }

    public MdConvRow onSelect(Runnable r) {
        addClickListener(e -> r.run());
        return this;
    }
}
