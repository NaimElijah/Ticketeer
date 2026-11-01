package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.shared.exception.AccountLockedException;
import com.ticketing.system.shared.exception.AuthenticationFailedException;
import com.ticketing.system.ui.presenters.auth.AdminLoginPresenter;
import com.ticketing.system.notifications.application.port.in.INotificationService;
import com.ticketing.system.shared.dto.NotificationDTO;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.session.NotificationSession;
import com.ticketing.system.ui.session.UnreadAlertsSendBack;
import com.vaadin.flow.server.VaadinSession;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;

class AdminLoginPresenterTest {

    private AuthenticationService authenticationService;
    private INotificationService notificationService;
    private AdminLoginPresenter presenter;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        authenticationService = mock(AuthenticationService.class);
        notificationService = mock(INotificationService.class);
        presenter = new AdminLoginPresenter(authenticationService, new UnreadAlertsSendBack(notificationService));
    }

    @AfterEach
    void tearDown() {
        MapBackedVaadinSession.uninstall();
    }

    // ---- the admin's sign-in on a browser a member used (I.6, ADR-012, #187) -------------------
    // The member's shown-but-unread alerts go back to pending first, while the session still names the
    // member; then the bell is emptied before the admin's auth is stored, so the admin's session never
    // holds a member's alerts.

    private static final int MEMBER_ID = 42;
    private static final AuthTokenDTO ADMIN_AUTH = new AuthTokenDTO("admin-jwt", 0L, 1, "admin", true);

    @Test
    void GivenAMemberSignedInBefore_WhenSignIn_ThenTheirAlertsAreSentBackTheBellClearedThenTheAdminStored() {
        AuthSession.storeAuth(new AuthTokenDTO("member-jwt", 0L, MEMBER_ID, "member", false));
        NotificationSession.store(List.of(new NotificationDTO("n-1", "DIRECT_MESSAGE", "SENT", "hi",
                LocalDateTime.of(2027, 7, 15, 17, 0))));
        VaadinSession session = VaadinSession.getCurrent();

        presenter.signIn(ADMIN_AUTH);

        verify(notificationService).revertSentToPending(MEMBER_ID);
        assertEquals(List.of(), NotificationSession.getAll(), "the member's alerts must leave the bell");
        assertTrue(AuthSession.isAdmin());
        assertEquals(ADMIN_AUTH.token(), AuthSession.token());
        // The bell is emptied (its two attributes set to null) before the admin's token is stored.
        InOrder order = inOrder(session);
        order.verify(session, times(2)).setAttribute(anyString(), isNull());
        order.verify(session).setAttribute(anyString(), eq(ADMIN_AUTH.token()));
    }

    @Test
    void GivenNoMemberSignedInBefore_WhenSignIn_ThenNothingIsSentBack() {
        presenter.signIn(ADMIN_AUTH);

        verifyNoInteractions(notificationService);
        assertTrue(AuthSession.isAdmin());
    }

    @Test
    void success_returnsSuccessWrappingTheToken() {
        AuthTokenDTO token = new AuthTokenDTO("jwt", 9_999_999_999L, 1, "root");
        when(authenticationService.signInAsAdmin("root", "pw")).thenReturn(token);

        AdminLoginPresenter.Outcome outcome = presenter.attemptAdminLogin("root", "pw");

        AdminLoginPresenter.Outcome.Success ok =
            assertInstanceOf(AdminLoginPresenter.Outcome.Success.class, outcome);
        assertSame(token, ok.authToken());
    }

    @Test
    void authenticationFailed_returnsInvalidCredentials() {
        when(authenticationService.signInAsAdmin("root", "wrong"))
            .thenThrow(new AuthenticationFailedException());

        assertInstanceOf(AdminLoginPresenter.Outcome.InvalidCredentials.class,
            presenter.attemptAdminLogin("root", "wrong"));
    }

    @Test
    void accountLocked_returnsLocked() {
        when(authenticationService.signInAsAdmin("root", "pw"))
            .thenThrow(new AccountLockedException("locked for 15 minutes"));

        assertInstanceOf(AdminLoginPresenter.Outcome.Locked.class,
            presenter.attemptAdminLogin("root", "pw"));
    }

    @Test
    void databaseUnavailable_returnsServiceUnavailable() {
        // A DB outage surfaces as a connectivity exception — must map to ServiceUnavailable.
        when(authenticationService.signInAsAdmin("root", "pw"))
            .thenThrow(new DataAccessResourceFailureException("could not get a connection"));

        assertInstanceOf(AdminLoginPresenter.Outcome.ServiceUnavailable.class,
            presenter.attemptAdminLogin("root", "pw"));
    }

    @Test
    void unexpectedRuntime_returnsFailure() {
        when(authenticationService.signInAsAdmin("root", "pw"))
            .thenThrow(new IllegalStateException("boom"));

        assertInstanceOf(AdminLoginPresenter.Outcome.Failure.class,
            presenter.attemptAdminLogin("root", "pw"));
    }
}
