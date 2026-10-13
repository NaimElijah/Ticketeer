package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.ticketing.system.notifications.domain.Notification;
import com.ticketing.system.notifications.domain.NotificationStatus;
import com.ticketing.system.notifications.domain.NotificationType;
import com.ticketing.system.support.BaseDomainTest;

// Unit tests for the Notification aggregate (UC-35/36/37).
// Extends BaseDomainTest so each tracked aggregate gets automatic
// invariant verification via track(aggregate) after the test runs.
class NotificationTest extends BaseDomainTest {

    /** A freshly-built PENDING notification, tracked for post-test invariant checks. */
    private Notification pending() {
        return track(new Notification(
                "n1", 7, NotificationType.PURCHASE_CONFIRMED,
                NotificationStatus.PENDING, "Your purchase is confirmed",
                LocalDateTime.now()));
    }

    @Test
    void givenPendingNotification_whenMarkSent_thenStatusSent() {
        Notification n = pending();
        n.markSent();
        assertTrue(n.isSent());
    }

    @Test
    void givenSentNotification_whenMarkRead_thenStatusRead() {
        Notification n = pending();
        n.markSent();
        n.markRead();
        assertTrue(n.isRead());
    }

    @Test
    void givenPendingNotification_whenMarkRead_thenThrows() {
        Notification n = pending();
        // markRead requires SENT; from PENDING it must throw before mutating state.
        assertThrows(IllegalStateException.class, n::markRead);
    }
}
