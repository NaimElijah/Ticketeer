package com.ticketing.system.ui.security;

import com.ticketing.system.notifications.application.port.in.INotificationService;
import com.ticketing.system.ui.session.AuthSession;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.server.VaadinSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Reverts SENT notifications back to PENDING when the user's Vaadin session
 * is destroyed (browser closed, tab killed, session timeout). This ensures
 * notifications the user never opened are re-delivered on the next login.
 *
 * Reads auth state from the session it is handed, through AuthSession's session-taking
 * readers, because VaadinSession.getCurrent() is not set in a session-destroy context.
 *
 * An admin's session is skipped: notifications are addressed by bare user id, and
 * admin ids share the member id space, so reverting by the admin's id would touch
 * the notifications of the member with that id (ADR-012).
 */
@Component
@Slf4j
public class NotificationSessionListener implements VaadinServiceInitListener {

    private final INotificationService notificationService;

    public NotificationSessionListener(INotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addSessionDestroyListener(e -> {
            VaadinSession session = e.getSession();
            session.lock();
            try {
                if (!AuthSession.isSignedIn(session) || AuthSession.isAdmin(session)) return;
                Integer userId = AuthSession.userId(session);
                if (userId == null) return;
                notificationService.revertSentToPending(userId);
            } catch (RuntimeException ex) {
                // Handled by logging: nothing above a session-destroy callback can act on it.
                log.warn("SENT notifications not sent back on session end; they will not be shown again: userId={}",
                        AuthSession.userId(session), ex);
            } finally {
                session.unlock();
            }
        });
    }
}
