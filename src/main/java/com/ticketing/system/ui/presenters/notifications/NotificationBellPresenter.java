package com.ticketing.system.ui.presenters.notifications;

import org.springframework.stereotype.Component;

import com.ticketing.system.notifications.application.port.in.INotificationService;
import com.ticketing.system.ui.session.AuthSession;

/**
 * Presenter for the top-bar notification bell's mark-read action. A Vaadin-free
 * POJO wrapping the one fire-and-forget {@link INotificationService} call, so the
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

    public NotificationBellPresenter(INotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** Mark one notification read for the signed-in member; no-op when signed out. */
    public void markRead(String notificationId) {
        Integer userId = AuthSession.userId();
        if (userId != null) {
            notificationService.markRead(userId, notificationId);
        }
    }
}
