package com.ticketing.system.ui.components.messaging;

import com.vaadin.flow.component.html.Div;

import java.util.List;

/**
 * Message thread with bubble layout. Each {@link Message#me} bubble
 * floats right (primary blue), counterparty bubbles float left (slate).
 * Ports {@code .md-thread / .md-msg / .md-msg-bubble} from the kit.
 *
 * <p>The thread is a named log (#188, WCAG 1.3.1, 2.1.1, 4.1.2): a message appended later is read
 * in order, and the scrolling thread is a Tab stop, so a keyboard user can scroll it.
 */
public class MdThread extends Div {

    private static final String ROLE = "role";
    private static final String ROLE_LOG = "log";
    private static final String TABINDEX = "tabindex";
    private static final String TAB_STOP = "0";
    private static final String ARIA_LABEL = "aria-label";
    private static final String NAME = "Messages";

    /**
     * One message bubble.
     *
     * @param from the sender's name, shown above the bubble
     * @param time the time text shown beside the name, already formatted
     * @param me   whether the viewer sent it, which floats the bubble right
     * @param text the message, shown as plain text
     */
    public record Message(String from, String time, boolean me, String text) { }

    /**
     * Builds the thread.
     *
     * @param msgs the messages, top to bottom as shown; not {@code null}
     */
    public MdThread(List<Message> msgs) {
        addClassName("md-thread");
        getElement().setAttribute(ROLE, ROLE_LOG);
        getElement().setAttribute(TABINDEX, TAB_STOP);
        getElement().setAttribute(ARIA_LABEL, NAME);
        for (Message m : msgs) append(m);
    }

    /** Adds {@code m} below the messages already shown. */
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
