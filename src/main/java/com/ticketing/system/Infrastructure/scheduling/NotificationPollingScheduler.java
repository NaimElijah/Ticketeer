package com.ticketing.system.Infrastructure.scheduling;

import com.ticketing.system.Core.Application.dto.NotificationDTO;
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
 * and refreshes the bell component — triggering a long-poll push to the browser.
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
                Integer userId = AuthSession.userId();
                if (userId == null) return;

                List<NotificationDTO> fresh;
                try {
                    fresh = dispatchService.deliverPending(userId);
                } catch (Exception e) {
                    log.warn("notification poll failed for userId={}: {}", userId, e.getMessage());
                    return;
                }

                if (fresh.isEmpty()) return;

                NotificationSession.merge(fresh);
                NotificationBellComponent bell = NotificationSession.getBell();
                if (bell != null) bell.refresh();
            });
        }
    }
}
