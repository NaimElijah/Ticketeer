package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ticketing.system.notifications.domain.Notification;
import com.ticketing.system.notifications.domain.NotificationStatus;
import com.ticketing.system.notifications.domain.NotificationType;
import com.ticketing.system.support.BaseDomainTest;

// Unit tests for the Notification aggregate (UC-35/36/37).
// Extends BaseDomainTest so each tracked aggregate gets automatic
// invariant verification via track(aggregate) after the test runs.
class NotificationTest extends BaseDomainTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2027, 7, 15, 17, 0);

    /** A freshly-built PENDING notification, tracked for post-test invariant checks. */
    private Notification pending() {
        return track(new Notification(
                "n1", 7, NotificationType.PURCHASE_CONFIRMED,
                NotificationStatus.PENDING, "Your purchase is confirmed",
                CREATED));
    }

    @Test
    void GivenPendingNotification_WhenMarkSent_ThenStatusSent() {
        Notification n = pending();
        n.markSent();
        assertTrue(n.isSent());
    }

    @Test
    void GivenSentNotification_WhenMarkRead_ThenStatusRead() {
        Notification n = pending();
        n.markSent();
        n.markRead();
        assertTrue(n.isRead());
    }

    // The message is composed from names and subjects nobody bounds, and its column is varchar(255):
    // a longer one would fail the insert on the async notify- thread, and the notice would be lost.
    @Test
    void GivenAMessageLongerThanItsColumn_WhenCreated_ThenItIsCutToTheColumnEndingInAnEllipsis() {
        String longMessage = "Event '" + "x".repeat(300) + "' has been cancelled.";

        Notification n = track(new Notification("n2", 7, NotificationType.EVENT_CANCELLED,
                NotificationStatus.PENDING, longMessage, CREATED));

        assertEquals(Notification.MAX_MESSAGE_LENGTH, n.getMessage().length());
        assertEquals(longMessage.substring(0, Notification.MAX_MESSAGE_LENGTH - 1) + "…", n.getMessage());
    }

    // An emoji is two UTF-16 units. Cutting between them would store half a character, which the
    // PostgreSQL driver encodes as '?'.
    @Test
    void GivenACutThatFallsInsideAnEmoji_WhenCreated_ThenTheWholeEmojiIsDropped() {
        String head = "x".repeat(Notification.MAX_MESSAGE_LENGTH - 2);
        String longMessage = head + "😀" + "y".repeat(10);

        Notification n = track(new Notification("n4", 7, NotificationType.EVENT_CANCELLED,
                NotificationStatus.PENDING, longMessage, CREATED));

        assertEquals(head + "…", n.getMessage());
    }

    @Test
    void GivenAMessageThatFitsItsColumn_WhenCreated_ThenItIsKeptWhole() {
        String fits = "y".repeat(Notification.MAX_MESSAGE_LENGTH);

        Notification n = track(new Notification("n3", 7, NotificationType.EVENT_CANCELLED,
                NotificationStatus.PENDING, fits, CREATED));

        assertEquals(fits, n.getMessage());
    }

    // A session ending in another browser or on another device sends SENT rows back to PENDING while
    // this session's bell still shows them, so a click on a PENDING notification must still record the
    // read (#187).
    @Test
    void GivenAPendingNotification_WhenMarkRead_ThenItIsRead() {
        Notification n = pending();

        n.markRead();

        assertTrue(n.isRead());
    }

    // ---- the delivery lifecycle's refusals (#189) ----

    @Test
    void GivenASentNotification_WhenMarkSentAgain_ThenRefusedAndStaysSent() {
        Notification n = pending();
        n.markSent();

        assertThrows(IllegalStateException.class, n::markSent);

        assertEquals(NotificationStatus.SENT, n.getStatus());
    }

    @Test
    void GivenAReadNotification_WhenMarkSent_ThenRefusedAndStaysRead() {
        Notification n = pending();
        n.markRead();

        assertThrows(IllegalStateException.class, n::markSent);

        assertEquals(NotificationStatus.READ, n.getStatus());
    }

    // A session ending sends a shown, unread notification back to PENDING, to be shown again.
    @Test
    void GivenASentNotification_WhenMarkPending_ThenPending() {
        Notification n = pending();
        n.markSent();

        n.markPending();

        assertEquals(NotificationStatus.PENDING, n.getStatus());
    }

    @Test
    void GivenAPendingNotification_WhenMarkPending_ThenRefusedAndStaysPending() {
        Notification n = pending();

        assertThrows(IllegalStateException.class, n::markPending);

        assertEquals(NotificationStatus.PENDING, n.getStatus());
    }

    // A read notification must not come back to the bell.
    @Test
    void GivenAReadNotification_WhenMarkPending_ThenRefusedAndStaysRead() {
        Notification n = pending();
        n.markRead();

        assertThrows(IllegalStateException.class, n::markPending);

        assertEquals(NotificationStatus.READ, n.getStatus());
    }

    @ParameterizedTest(name = "recipient={0}")
    @ValueSource(ints = {0, -1})
    void GivenARecipientBelowOne_WhenCreated_ThenRefused(int recipient) {
        assertThrows(IllegalStateException.class, () -> new Notification("n5", recipient,
                NotificationType.PURCHASE_CONFIRMED, NotificationStatus.PENDING, "Your purchase is confirmed", CREATED));
    }

    @Test
    void GivenAReadNotification_WhenMarkRead_ThenThrowsAndStaysRead() {
        Notification n = pending();
        n.markSent();
        n.markRead();

        assertThrows(IllegalStateException.class, n::markRead);
        assertTrue(n.isRead());
    }
}
