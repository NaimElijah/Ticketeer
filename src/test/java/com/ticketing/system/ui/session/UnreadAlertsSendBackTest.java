package com.ticketing.system.ui.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ticketing.system.notifications.application.port.in.INotificationService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.testutil.LogCapture;

import ch.qos.logback.classic.Level;

/**
 * Before a session drops a member's alerts (sign-out, a dead token, an admin sign-in on the same
 * browser), the shown-but-unread ones go back to pending, so the next login shows them again (I.6).
 * Admin and member ids share one id space (ADR-012), so an admin's session sends nothing back.
 */
class UnreadAlertsSendBackTest {

    private static final int MEMBER_ID = 42;

    private INotificationService notificationService;
    private UnreadAlertsSendBack sendBack;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        notificationService = mock(INotificationService.class);
        sendBack = new UnreadAlertsSendBack(notificationService);
    }

    @AfterEach
    void tearDown() {
        MapBackedVaadinSession.uninstall();
    }

    @Test
    void GivenASignedInMember_WhenSentBack_ThenTheMembersAlertsAreSentBack() {
        AuthSession.storeAuth(new AuthTokenDTO("member-jwt", 0L, MEMBER_ID, "member", false));

        sendBack.sendBackForSignedInMember();

        verify(notificationService).revertSentToPending(MEMBER_ID);
    }

    @Test
    void GivenAnAdminSession_WhenSentBack_ThenNothingIsSentBack() {
        AuthSession.storeAuth(new AuthTokenDTO("admin-jwt", 0L, MEMBER_ID, "admin", true));

        sendBack.sendBackForSignedInMember();

        verifyNoInteractions(notificationService);
    }

    @Test
    void GivenNobodySignedIn_WhenSentBack_ThenNothingIsSentBack() {
        sendBack.sendBackForSignedInMember();

        verifyNoInteractions(notificationService);
    }

    // Its callers clear the session next and must finish (FreshGuestSession.restore never throws), so a
    // failure ends here, and the WARN is all that is left of it.
    @Test
    void GivenTheSendBackFails_WhenSentBack_ThenItDoesNotThrowAndAWarnCarriesTheExceptionAndTheUser() {
        AuthSession.storeAuth(new AuthTokenDTO("member-jwt", 0L, MEMBER_ID, "member", false));
        doThrow(new DataAccessResourceFailureException("store unavailable"))
                .when(notificationService).revertSentToPending(MEMBER_ID);

        try (LogCapture logs = LogCapture.of(UnreadAlertsSendBack.class)) {
            assertDoesNotThrow(sendBack::sendBackForSignedInMember);

            assertEquals(List.of("SENT notifications not sent back; they stay SENT until another of the member's"
                    + " sessions ends: userId=" + MEMBER_ID), logs.messagesAt(Level.WARN));
            assertEquals(List.of(DataAccessResourceFailureException.class.getName()),
                    logs.throwableClassNamesAt(Level.WARN));
        }
    }
}
