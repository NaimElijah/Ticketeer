package com.ticketing.system.ui.components.messaging;

import com.vaadin.flow.component.html.Div;

import java.util.List;

/**
 * Message thread with bubble layout. Each {@link Message#me} bubble
 * floats right (primary blue), counterparty bubbles float left (slate).
 * Ports {@code .md-thread / .md-msg / .md-msg-bubble} from the kit.
 */
public class MdThread extends Div {

    public record Message(String from, String time, boolean me, String text) { }

    public MdThread(List<Message> msgs) {
        addClassName("md-thread");
        for (Message m : msgs) append(m);
    }

    public MdThread append(Message m) {
        Div msg = new Div();
        msg.addClassName("md-msg");
        if (m.me) msg.addClassName("me");

        Div meta = new Div();
        meta.addClassName("md-msg-meta");
        meta.setText(m.from + " · " + m.time);

        Div bubble = new Div();
        bubble.addClassName("md-msg-bubble");
        bubble.setText(m.text);

        msg.add(meta, bubble);
        add(msg);
        return this;
    }
}
