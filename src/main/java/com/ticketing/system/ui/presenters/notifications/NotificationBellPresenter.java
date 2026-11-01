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
     * @return {@link MarkReadOutcome.Success} once the read is stored; {@link MarkReadOutcome.NotAuthenticated}
     *         when nobody is signed in; {@link MarkReadOutcome.Failure} with the reason to show when the
     *         store refused it. Only on success does the bell show the item read, so it agrees with what
     *         the next login delivers
     */
    public MarkReadOutcome markRead(String notificationId) {
        Integer userId = AuthSession.userId();
        if (userId == null) {
            return new MarkReadOutcome.NotAuthenticated();
        }
        try {
            notificationService.markRead(userId, notificationId);
            return new MarkReadOutcome.Success();
        } catch (RuntimeException e) {
            return new MarkReadOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /** What became of one mark-read: stored, nobody signed in, or refused with a reason to show. */
    public sealed interface MarkReadOutcome {
        record Success() implements MarkReadOutcome { }
        record NotAuthenticated() implements MarkReadOutcome { }
        record Failure(String reason) implements MarkReadOutcome { }
    }
}
