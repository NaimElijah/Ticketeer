package com.ticketing.system.ui.security;

import com.ticketing.system.notifications.application.port.in.INotificationService;
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
 * Reads auth state directly from session attributes to avoid relying on
 * VaadinSession.getCurrent(), which is not set in a session-destroy context.
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
                Boolean signedIn = (Boolean) session.getAttribute("authSession.signedIn");
                if (!Boolean.TRUE.equals(signedIn)) return;
                Integer userId = (Integer) session.getAttribute("authSession.userId");
                if (userId == null) return;
                notificationService.revertSentToPending(userId);
            } catch (Exception ex) {
                log.warn("failed to revert notifications on session destroy: {}", ex.getMessage());
            } finally {
                session.unlock();
            }
        });
    }
}
