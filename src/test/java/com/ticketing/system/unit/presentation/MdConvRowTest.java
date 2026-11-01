package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.ticketing.system.ui.components.messaging.MdConvRow;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Span;

/**
 * Unit tests for {@link MdConvRow}'s unread badge, which an inbox clears in place when the reader opens
 * the thread (#187). Constructed headless (no UI), like {@code LkTextRowsTest}.
 */
class MdConvRowTest {

    private static final String BADGE_CLASS = "md-conv-dot";

    @Test
    void GivenARowWithAnUnreadBadge_WhenClearUnread_ThenTheBadgeIsGone() {
        MdConvRow row = row("3");
        assertEquals(List.of("3"), badges(row));

        row.clearUnread();

        assertEquals(List.of(), badges(row));
    }

    @Test
    void GivenAClearedRow_WhenClearUnreadAgain_ThenItStaysClearedAndKeepsItsSubject() {
        MdConvRow row = row("3");
        row.clearUnread();

        row.clearUnread();

        assertEquals(List.of(), badges(row));
        assertEquals(List.of("Parking?", "alice", "5m"), texts(row));
    }

    @Test
    void GivenARowWithoutABadge_WhenClearUnread_ThenNothingChanges() {
        MdConvRow row = row(null);

        row.clearUnread();

        assertEquals(List.of(), badges(row));
        assertEquals(List.of("Parking?", "alice", "5m"), texts(row));
    }

    private static MdConvRow row(String unread) {
        return new MdConvRow("comment", "Parking?", "alice", "5m", unread);
    }

    private static List<String> badges(Component root) {
        List<String> found = new ArrayList<>();
        collect(root, span -> span.hasClassName(BADGE_CLASS), found);
        return found;
    }

    // Every text span other than the badge: the subject, the sender and the time.
    private static List<String> texts(Component root) {
        List<String> found = new ArrayList<>();
        collect(root, span -> !span.hasClassName(BADGE_CLASS) && !span.getText().isEmpty(), found);
        return found;
    }

    private static void collect(Component component, Predicate<Span> keep, List<String> found) {
        if (component instanceof Span span && keep.test(span)) {
            found.add(span.getText());
        }
        component.getChildren().forEach(child -> collect(child, keep, found));
    }
}
