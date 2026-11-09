package com.ticketing.system.bootstrap.scheduling;

import com.ticketing.system.notifications.application.service.NotificationDispatchService;
import com.ticketing.system.ui.components.NotificationBellComponent;
import com.ticketing.system.ui.session.NotificationSession;
import com.ticketing.system.ui.session.SessionIdentity;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.UIDetachedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls for new notifications for every connected user on a fixed interval.
 * For each live UI, acquires the Vaadin session lock via ui.access(), fetches
 * any newly generated PENDING notifications, merges them into NotificationSession,
 * and brings that UI's bell up to date with the session's inbox, which another tab's
 * poll may have filled — re-rendering it, and so pushing to the browser, only when
 * the inbox changed.
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

    // What a failure's log line shows for the user id when it fails before the member is resolved.
    private static final String UNKNOWN_USER_ID = "unknown";

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

    /**
     * One scheduled tick: runs a delivery for every UI connected at the time of the call, each under
     * its UI's session lock. A delivery runs on this thread when the lock is free and is left to the
     * lock's holder otherwise, so a tick can last as long as its deliveries.
     */
    @Scheduled(fixedDelayString = "${notifications.poll-delay-ms:60000}")
    public void poll() {
        for (UI ui : uiRegistry.getActiveUIs()) {
            try {
                ui.access(() -> deliverTo(ui));
            } catch (UIDetachedException detached) {
                // The UI closed between the registry read and this access; the others still get theirs.
                log.debug("skipping a UI that detached before its notification poll");
            }
        }
    }

    // Runs under the UI's session lock, so the session state read here is that UI's.
    private void deliverTo(UI ui) {
        String userId = UNKNOWN_USER_ID;
        try {
            // Notifications are addressed by bare user id, and admin ids share the member id space, so only a
            // live member's id may drain an inbox (ADR-012).
            int memberId = sessionIdentity.memberUserId();
            if (memberId == SessionIdentity.NO_MEMBER_USER_ID) {
                return;
            }
            userId = String.valueOf(memberId);
            NotificationSession.merge(dispatchService.deliverPending(memberId));
            // Even with nothing fresh: another tab of this session may have drained the pending
            // notifications into the shared inbox, or marked some read, since this bell last rendered.
            NotificationBellComponent bell = NotificationSession.getBell();
            if (bell != null) {
                bell.refreshIfChanged();
            }
        } catch (RuntimeException e) {
            log.warn("notification poll failed: uiId={} userId={}; the UI is polled again on the next tick",
                    ui.getUIId(), userId, e);
        }
    }
}
