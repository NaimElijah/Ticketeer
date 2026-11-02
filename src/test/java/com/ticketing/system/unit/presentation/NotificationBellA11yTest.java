package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.NotificationDTO;
import com.ticketing.system.ui.components.NotificationBellComponent;
import com.ticketing.system.ui.presenters.notifications.NotificationBellPresenter.MarkReadOutcome;
import com.ticketing.system.ui.session.NotificationSession;
import com.vaadin.flow.dom.Element;

/**
 * The notification bell tells a screen reader what a sighted user sees (#188, A-M6, U-A3, U-A4, P-m7;
 * WCAG 1.1.1, 1.3.1, 1.4.1, 2.4.3, 4.1.2):
 * <ul>
 *   <li>its name carries the unread count ("Notifications, 3 unread"), which today only the badge shows;</li>
 *   <li>each unread row says "unread" in text, not only by its tint and dot;</li>
 *   <li>an empty inbox is a message, not a button that does nothing;</li>
 *   <li>a refresh updates the bell in place, so the button a keyboard user is on is not destroyed under
 *       them (the poller refreshes it every tick). The server cannot see the browser's focus, so the test
 *       checks the bell's button survives a refresh.</li>
 * </ul>
 */
class NotificationBellA11yTest {

    // ---- F9: notification bell and panel (#188) ----

    private static final LocalDateTime NOW = LocalDateTime.of(2027, 7, 15, 17, 0);
    private static final Pattern THREE_UNREAD = Pattern.compile("(?i)\\b3\\b.*unread|unread.*\\b3\\b");
    private static final Pattern UNREAD = Pattern.compile("(?i)\\bunread\\b");
    private static final String ROW_TEXT = "Message ";

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
        ViewHarness.signInMember();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenThreeUnreadNotifications_WhenTheBellRenders_ThenItsNameSaysThreeUnread() {
        NotificationSession.store(List.of(unread("n1", 0), unread("n2", 1), unread("n3", 2), read("n4", 3)));

        Element bell = bellButton(bell());

        assertTrue(THREE_UNREAD.matcher(A11y.name(bell)).find(),
            "the bell's name must carry the unread count, e.g. \"Notifications, 3 unread\": " + A11y.describe(bell));
    }

    @Test
    void GivenUnreadAndReadNotifications_WhenThePanelRenders_ThenOnlyTheUnreadRowsSayUnreadInText() {
        NotificationSession.store(List.of(unread("n1", 0), read("n2", 1)));

        NotificationBellComponent bell = bell();

        assertTrue(UNREAD.matcher(A11y.text(row(bell, "n1"))).find(),
            "an unread row must say \"unread\" in text (visually hidden is fine): " + A11y.describe(row(bell, "n1")));
        assertFalse(UNREAD.matcher(A11y.text(row(bell, "n2"))).find(),
            "a read row must not say \"unread\": " + A11y.describe(row(bell, "n2")));
    }

    @Test
    void GivenAnEmptyInbox_WhenThePanelRenders_ThenTheEmptyStateIsNotAButton() {
        NotificationSession.store(List.of());

        NotificationBellComponent bell = bell();

        List<Element> emptyState = A11y.elements(bell, e -> A11y.text(e).toLowerCase(Locale.ROOT)
            .contains("all caught up") && A11y.isButton(e));
        assertTrue(emptyState.isEmpty(),
            "the empty state is a message, not a button that does nothing: "
                + emptyState.stream().map(A11y::describe).toList());
    }

    @Test
    void GivenTheBellIsShown_WhenANewNotificationArrivesAndItRefreshes_ThenTheSameBellButtonShowsTheNewCount() {
        NotificationSession.store(List.of(unread("n1", 0)));
        NotificationBellComponent bell = bell();
        Element before = bellButton(bell);

        NotificationSession.store(List.of(unread("n1", 0), unread("n2", 1), unread("n3", 2)));
        bell.refresh();

        Element after = bellButton(bell);
        // Flow builds a fresh Element wrapper per lookup; equals compares the state node underneath.
        assertTrue(before.equals(after),
            "a refresh must update the bell in place, not replace the button a keyboard user may be on");
        assertTrue(THREE_UNREAD.matcher(A11y.name(after)).find(),
            "the refreshed bell must announce the new count: " + A11y.describe(after));
    }

    @Test
    void GivenTheBellIsShown_WhenItRefreshesWithNothingNew_ThenTheBellButtonIsKept() {
        NotificationSession.store(List.of(unread("n1", 0)));
        NotificationBellComponent bell = bell();
        Element before = bellButton(bell);

        bell.refresh();

        assertTrue(before.equals(bellButton(bell)), "a refresh must not replace the bell's button");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static NotificationBellComponent bell() {
        return new NotificationBellComponent(id -> new MarkReadOutcome.Success(), createdAt -> "");
    }

    // The bell is the button that opens the notification popover, outside it.
    private static Element bellButton(NotificationBellComponent bell) {
        List<Element> buttons = A11y.elements(bell, e -> A11y.isButton(e) && !inPopover(e));
        assertEquals(1, buttons.size(), "the bell must render one button outside its panel; it renders: "
            + buttons.stream().map(A11y::describe).toList());
        return buttons.get(0);
    }

    private static Element row(NotificationBellComponent bell, String id) {
        return A11y.elements(bell, e -> inPopover(e) && A11y.isButton(e) && A11y.text(e).contains(ROW_TEXT + id))
            .stream()
            .findFirst()
            .orElseThrow(() -> new AssertionError("no row for " + id));
    }

    private static boolean inPopover(Element e) {
        for (Element a = e; a != null; a = a.getParent()) {
            if ("vaadin-popover".equals(a.getTag())) {
                return true;
            }
        }
        return false;
    }

    private static NotificationDTO unread(String id, int minutesAgo) {
        return new NotificationDTO(id, "DIRECT_MESSAGE", "SENT", ROW_TEXT + id, NOW.minusMinutes(minutesAgo));
    }

    private static NotificationDTO read(String id, int minutesAgo) {
        return new NotificationDTO(id, "DIRECT_MESSAGE", "READ", ROW_TEXT + id, NOW.minusMinutes(minutesAgo));
    }
}
