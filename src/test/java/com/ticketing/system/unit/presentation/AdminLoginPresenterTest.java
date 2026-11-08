package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CheckoutResultDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.shared.exception.AccountLockedException;
import com.ticketing.system.shared.exception.AuthenticationFailedException;
import com.ticketing.system.ui.presenters.auth.AdminLoginPresenter;
import com.ticketing.system.notifications.application.port.in.INotificationService;
import com.ticketing.system.shared.dto.NotificationDTO;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.session.NotificationSession;
import com.ticketing.system.ui.session.OrderSession;
import com.ticketing.system.ui.session.UnreadAlertsSendBack;
import com.vaadin.flow.server.VaadinSession;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import jakarta.persistence.PersistenceException;
import com.ticketing.system.ui.support.ServiceErrors;

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
    private static final String LOCKED_REASON = "locked for 15 minutes";

    @Test
    void GivenAMemberSignedInBefore_WhenSignIn_ThenTheirAlertsAreSentBackTheBellIsEmptyAndTheAdminIsStored() {
        AuthSession.storeAuth(new AuthTokenDTO("member-jwt", 0L, MEMBER_ID, "member", false));
        NotificationSession.store(List.of(new NotificationDTO("n-1", "DIRECT_MESSAGE", "SENT", "hi",
                LocalDateTime.of(2027, 7, 15, 17, 0))));

        presenter.signIn(ADMIN_AUTH);

        verify(notificationService).revertSentToPending(MEMBER_ID);
        assertEquals(List.of(), NotificationSession.getAll(), "the member's alerts must leave the bell");
        assertTrue(AuthSession.isAdmin());
        assertEquals(ADMIN_AUTH.token(), AuthSession.token());
    }

    // The bell is emptied before the admin's token is stored, so the session never pairs the admin's token
    // with the member's alerts. The bell itself lives on its UI, not on the session (UI-B8).
    @Test
    void GivenAMemberSignedInBefore_WhenSignIn_ThenTheBellIsAlreadyEmptyWhenTheAdminTokenIsStored() {
        AuthSession.storeAuth(new AuthTokenDTO("member-jwt", 0L, MEMBER_ID, "member", false));
        NotificationSession.store(List.of(new NotificationDTO("n-1", "DIRECT_MESSAGE", "SENT", "hi",
                LocalDateTime.of(2027, 7, 15, 17, 0))));
        List<List<NotificationDTO>> bellWhenTheAdminTokenIsStored = new ArrayList<>();
        doAnswer(call -> bellWhenTheAdminTokenIsStored.add(NotificationSession.getAll()))
                .when(VaadinSession.getCurrent()).setAttribute(anyString(), eq(ADMIN_AUTH.token()));

        presenter.signIn(ADMIN_AUTH);

        assertEquals(List.of(List.of()), bellWhenTheAdminTokenIsStored,
                "the admin's token is stored once, onto an empty bell");
    }

    // The kept order confirmation belongs to one visitor and one visit (#187): a sign-in starts another.
    @Test
    void GivenAKeptCheckoutConfirmation_WhenAnAdminSignsIn_ThenItIsDropped() {
        Instant keptAt = Instant.parse("2027-07-15T17:00:00Z");
        OrderSession.setResult(new CheckoutResultDTO(75.0, 42, 9001,
            List.of(new CheckoutResultDTO.IssuedTicketDTO(1, "TIX-0001-A1"))), keptAt);

        presenter.signIn(ADMIN_AUTH);

        assertTrue(OrderSession.result(keptAt).isEmpty(), "the previous visit's order confirmation is dropped");
    }

    @Test
    void GivenNoMemberSignedInBefore_WhenSignIn_ThenNothingIsSentBack() {
        presenter.signIn(ADMIN_AUTH);

        verifyNoInteractions(notificationService);
        assertTrue(AuthSession.isAdmin());
    }

    @Test
    void GivenValidCredentials_WhenAttemptAdminLogin_ThenReturnsSuccessWrappingTheToken() {
        AuthTokenDTO token = new AuthTokenDTO("jwt", 9_999_999_999L, 1, "root");
        when(authenticationService.signInAsAdmin("root", "pw")).thenReturn(token);

        AdminLoginPresenter.Outcome outcome = presenter.attemptAdminLogin("root", "pw");

        AdminLoginPresenter.Outcome.Success ok =
            assertInstanceOf(AdminLoginPresenter.Outcome.Success.class, outcome);
        assertSame(token, ok.authToken());
    }

    @Test
    void GivenAuthenticationFails_WhenAttemptAdminLogin_ThenReturnsInvalidCredentials() {
        when(authenticationService.signInAsAdmin("root", "wrong"))
            .thenThrow(new AuthenticationFailedException());

        assertInstanceOf(AdminLoginPresenter.Outcome.InvalidCredentials.class,
            presenter.attemptAdminLogin("root", "wrong"));
    }

    @Test
    void GivenALockedAccount_WhenAttemptAdminLogin_ThenLockedWithTheLockReason() {
        when(authenticationService.signInAsAdmin("root", "pw"))
            .thenThrow(new AccountLockedException(LOCKED_REASON));

        assertEquals(new AdminLoginPresenter.Outcome.Locked(LOCKED_REASON),
            presenter.attemptAdminLogin("root", "pw"));
    }

    @Test
    void GivenTheDatabaseIsUnavailable_WhenAttemptAdminLogin_ThenReturnsServiceUnavailable() {
        // A DB outage surfaces as a connectivity exception — must map to ServiceUnavailable.
        when(authenticationService.signInAsAdmin("root", "pw"))
            .thenThrow(new DataAccessResourceFailureException("could not get a connection"));

        assertInstanceOf(AdminLoginPresenter.Outcome.ServiceUnavailable.class,
            presenter.attemptAdminLogin("root", "pw"));
    }

    @Test
    void GivenAnIllegalStateException_WhenAttemptAdminLogin_ThenFailureCarriesItsMessage() {
        when(authenticationService.signInAsAdmin("root", "pw"))
            .thenThrow(new IllegalStateException("boom"));

        assertEquals(new AdminLoginPresenter.Outcome.Failure("boom"),
            presenter.attemptAdminLogin("root", "pw"));
    }

    // ---- an internal failure reads as generic text, never as its own (#189) ----

    @Test
    void GivenAnInternalFailure_WhenAttemptAdminLogin_ThenFailureCarriesTheGenericText() {
        when(authenticationService.signInAsAdmin("root", "pw"))
            .thenThrow(new PersistenceException("could not execute statement [insert into sessions]"));

        assertEquals(new AdminLoginPresenter.Outcome.Failure(ServiceErrors.GENERIC_FAILURE_MESSAGE),
            presenter.attemptAdminLogin("root", "pw"));
    }
}
