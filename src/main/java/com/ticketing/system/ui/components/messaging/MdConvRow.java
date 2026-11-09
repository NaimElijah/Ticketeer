package com.ticketing.system.ui.components.messaging;

import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBadge;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;

/**
 * Conversation list row used by master/detail inboxes (Support inbox,
 * Owner inquiries, Admin complaint queue). Ports {@code .md-conv} from
 * the React kit.
 *
 * <p>The row says in words what its look shows (#188, WCAG 1.3.1, 1.4.1, 4.1.2): the selected row
 * is {@code aria-current}, the unread badge reads as "2 unread", and a closed thread says "Closed".
 */
public class MdConvRow extends NativeButton {

    private static final String ARIA_CURRENT = "aria-current";
    // The badge shows a bare count; the hidden word makes the row read "2 unread", not "2".
    private static final String UNREAD_SUFFIX = " unread";
    private static final String CLOSED = "Closed";

    private boolean active;
    private Span unreadDot;
    private final Div top;

    /**
     * @param iconName the {@link LkIcon} glyph name shown at the row's start
     * @param subject  the conversation's subject
     * @param who      the other party, under the subject
     * @param time     the time or age text at the row's end, already formatted
     * @param unread   the unread count as text for the badge; {@code null} or empty shows no badge
     */
    public MdConvRow(String iconName, String subject, String who, String time, String unread) {
        addClassName("md-conv");

        Span ic = new Span();
        ic.addClassName("md-conv-ic");
        ic.add(new LkIcon(iconName, 17));

        Div body = new Div();
        body.addClassName("md-conv-body");
        top = new Div();
        top.addClassName("md-conv-top");
        Span subj = new Span(subject);
        subj.addClassName("md-conv-subj");
        top.add(subj);
        if (unread != null && !unread.isEmpty()) {
            unreadDot = new Span(unread);
            unreadDot.addClassName("md-conv-dot");
            Span unreadWord = new Span(UNREAD_SUFFIX);
            unreadWord.addClassName(Lk.VISUALLY_HIDDEN);
            unreadDot.add(unreadWord);
            top.add(unreadDot);
        }
        Span whoSpan = new Span(who);
        whoSpan.addClassName("md-conv-who");
        body.add(top, whoSpan);

        Span timeSpan = new Span(time);
        timeSpan.addClassName("md-conv-time");

        add(ic, body, timeSpan);
    }

    /** Shows the row as the selected one; {@code active(true)}. */
    public MdConvRow active() { return active(true); }

    /**
     * Shows the row as the selected one, or not: the tint, and {@code aria-current} for a screen reader.
     *
     * @param on whether this row's conversation is the one shown
     * @return this row, for chaining
     */
    public MdConvRow active(boolean on) {
        if (on && !active) { addClassName("on"); active = true; }
        else if (!on && active) { removeClassName("on"); active = false; }
        if (active) {
            getElement().setAttribute(ARIA_CURRENT, "true");
        } else {
            getElement().removeAttribute(ARIA_CURRENT);
        }
        return this;
    }

    /**
     * Marks the thread closed with a "Closed" badge beside its subject, so the state is not shown by
     * dimming alone.
     *
     * @return this row, for chaining
     */
    public MdConvRow closed() {
        top.add(new LkBadge(CLOSED, LkBadge.Tone.muted).small());
        return this;
    }

    /** Removes the unread badge, once the thread has been marked read. A row without one is left as is. */
    public MdConvRow clearUnread() {
        if (unreadDot != null) {
            unreadDot.removeFromParent();
            unreadDot = null;
        }
        return this;
    }

    /** Runs {@code r} when the row is clicked; each call adds another listener. */
    public MdConvRow onSelect(Runnable r) {
        addClickListener(e -> r.run());
        return this;
    }
}
