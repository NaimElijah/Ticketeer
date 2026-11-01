package com.ticketing.system.ui.session;

import org.springframework.stereotype.Component;

import com.ticketing.system.notifications.application.port.in.INotificationService;

import lombok.extern.slf4j.Slf4j;

/**
 * Sends the signed-in member's shown-but-unread alerts back to pending before this session drops
 * them, so the member's next login shows them again (I.6).
 */
@Component
@Slf4j
public class UnreadAlertsSendBack {

    private final INotificationService notificationService;

    public UnreadAlertsSendBack(INotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * Sends the current session's member's SENT alerts back to PENDING. Does nothing when nobody is
     * signed in, or when the session is an admin's: admin and member ids share one id space (ADR-012),
     * so an admin's id would name some member's alerts.
     *
     * <p>Never throws: its callers clear the session next and must finish. A failure is logged at WARN
     * with the exception and the member's id, and the alerts stay SENT until another of the member's
     * signed-in sessions ends.
     */
    public void sendBackForSignedInMember() {
        if (!AuthSession.isSignedIn() || AuthSession.isAdmin()) {
            return;
        }
        Integer userId = AuthSession.userId();
        if (userId == null) {
            return;
        }
        try {
            notificationService.revertSentToPending(userId);
        } catch (RuntimeException e) {
            // Handled by logging: the caller goes on clearing the session whatever happens here.
            log.warn("SENT notifications not sent back; they stay SENT until another of the member's sessions"
                    + " ends: userId={}", userId, e);
        }
    }
}
