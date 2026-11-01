package com.ticketing.system.bootstrap.scheduling;

import com.ticketing.system.shared.dto.NotificationDTO;
import com.ticketing.system.notifications.application.service.NotificationDispatchService;
import com.ticketing.system.ui.components.NotificationBellComponent;
import com.ticketing.system.ui.session.NotificationSession;
import com.ticketing.system.ui.session.SessionIdentity;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.UIDetachedException;
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
 * <p>Only a member whose token is still live gets anything, checked through {@link SessionIdentity} as
 * {@code AuthBootstrap} checks it: a dead token signs that visitor out and delivers nothing, and an
 * admin is never a member, since admins have no notification inbox. A UI that detached before its turn
 * is skipped, and a failure for one UI is logged with its exception; neither stops the others.
 *
 * <p>Real-time WebSocket/SSE delivery (#225) will replace this when implemented.
 */
@Component
@Slf4j
public class NotificationPollingScheduler {

    private final ActiveUiRegistry uiRegistry;
    private final NotificationDispatchService dispatchService;
    private final SessionIdentity sessionIdentity;

    /**
     * @param uiRegistry      the UIs currently connected
     * @param dispatchService hands over a member's pending notifications
     * @param sessionIdentity resolves the live member of the UI whose session lock is held
     */
    public NotificationPollingScheduler(ActiveUiRegistry uiRegistry,
                                        NotificationDispatchService dispatchService,
                                        SessionIdentity sessionIdentity) {
        this.uiRegistry = uiRegistry;
        this.dispatchService = dispatchService;
        this.sessionIdentity = sessionIdentity;
    }

    @Scheduled(fixedDelayString = "${notifications.poll-delay-ms:60000}")
    public void poll() {
        for (UI ui : uiRegistry.getActiveUIs()) {
            try {
                ui.access(this::deliverToCurrentUi);
            } catch (UIDetachedException detached) {
                // The UI closed between the registry read and this access; the others still get theirs.
                log.debug("skipping a UI that detached before its notification poll");
            }
        }
    }

    // Runs under the UI's session lock, so the session state read here is that UI's.
    private void deliverToCurrentUi() {
        try {
            // Notifications are addressed by bare user id, and admin ids share the member id space, so only a
            // live member's id may drain an inbox (ADR-012).
            int userId = sessionIdentity.memberUserId();
            if (userId == SessionIdentity.NO_MEMBER_USER_ID) {
                return;
            }
            List<NotificationDTO> fresh = dispatchService.deliverPending(userId);
            if (fresh.isEmpty()) {
                return;
            }
            NotificationSession.merge(fresh);
            NotificationBellComponent bell = NotificationSession.getBell();
            if (bell != null) {
                bell.refresh();
            }
        } catch (RuntimeException e) {
            log.warn("notification poll failed for one UI; it is polled again on the next tick", e);
        }
    }
}
