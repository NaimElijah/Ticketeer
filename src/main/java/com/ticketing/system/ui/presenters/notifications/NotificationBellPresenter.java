package com.ticketing.system.ui.presenters.notifications;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.ticketing.system.notifications.application.port.in.INotificationService;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.support.DisplayTime;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * Presenter for the top-bar notification bell's mark-read action. A Vaadin-free
 * POJO wrapping the one {@link INotificationService} call, so the
 * three layout shells ({@code MainLayout}, {@code WorkspaceLayout},
 * {@code PlatformAdminLayout}) don't reach into the application layer directly.
 *
 * <p>The bell's data is read from {@code NotificationSession} by
 * {@code NotificationBellComponent}; this presenter only handles the write
 * (mark-read) side, resolving the user from {@link AuthSession}.
 */
@Component
public class NotificationBellPresenter {

    private final INotificationService notificationService;
    private final DisplayTime displayTime;

    public NotificationBellPresenter(INotificationService notificationService, DisplayTime displayTime) {
        this.notificationService = notificationService;
        this.displayTime = displayTime;
    }

    /** The age shown next to a notification ("5m", "Yesterday"); empty when unknown. */
    public String relativeTime(LocalDateTime createdAt) {
        return displayTime.ageForNotification(createdAt);
    }

    /**
     * Mark one notification read for the signed-in member.
     *
     * @param notificationId the notification the member clicked
     * @return whether the read was stored; {@code false} when nobody is signed in or the store failed,
     *         so the bell keeps the item unread and agrees with what the next login delivers
     */
    public boolean markRead(String notificationId) {
        Integer userId = AuthSession.userId();
        if (userId == null) {
            return false;
        }
        try {
            notificationService.markRead(userId, notificationId);
            return true;
        } catch (RuntimeException e) {
            // Handled by leaving the item unread. The bell shows no message, so the text is discarded;
            // the call is for its logging: like every presenter, an internal failure is logged at ERROR
            // with the exception, which LoggingAspect's one-line WARN does not carry.
            ServiceErrors.userMessage(e);
            return false;
        }
    }
}
