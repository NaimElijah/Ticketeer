package com.ticketing.system.bootstrap.scheduling;

import com.ticketing.system.shared.dto.NotificationDTO;
import com.ticketing.system.notifications.application.service.NotificationDispatchService;
import com.ticketing.system.ui.components.NotificationBellComponent;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.NotificationSession;
import com.vaadin.flow.component.UI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polls for new notifications for every connected user on a fixed interval.
 * For each live UI, acquires the Vaadin session lock via ui.access(), fetches
 * any newly generated PENDING notifications, merges them into NotificationSession,
 * and brings that UI's bell up to date with the session's inbox, which another tab's
 * poll may have filled — re-rendering it, and so pushing to the browser, only when
 * the inbox changed.
 * Admin UIs are skipped: admins have no notification inbox.
 *
 * Real-time WebSocket/SSE delivery (#225) will replace this when implemented.
 */
@Component
@Slf4j
public class NotificationPollingScheduler {

    private final ActiveUiRegistry uiRegistry;
    private final NotificationDispatchService dispatchService;

    public NotificationPollingScheduler(ActiveUiRegistry uiRegistry,
                                        NotificationDispatchService dispatchService) {
        this.uiRegistry = uiRegistry;
        this.dispatchService = dispatchService;
    }

    @Scheduled(fixedDelayString = "${notifications.poll-delay-ms:60000}")
    public void poll() {
        for (UI ui : uiRegistry.getActiveUIs()) {
            ui.access(() -> {
                if (!AuthSession.isSignedIn()) return;
                // Notifications are addressed by bare user id, and admin ids share the member id space:
                // an admin's UI would drain the inbox of the member whose id equals the admin's (ADR-012).
                if (AuthSession.isAdmin()) return;
                Integer userId = AuthSession.userId();
                if (userId == null) return;

                List<NotificationDTO> fresh;
                try {
                    fresh = dispatchService.deliverPending(userId);
                } catch (RuntimeException e) {
                    log.warn("notification poll failed for userId={}", userId, e);
                    return;
                }

                NotificationSession.merge(fresh);
                // Even with nothing fresh: another tab of this session may have drained the pending
                // notifications into the shared inbox, or marked some read, since this bell last rendered.
                NotificationBellComponent bell = NotificationSession.getBell();
                if (bell != null) bell.refreshIfChanged();
            });
        }
    }
}
