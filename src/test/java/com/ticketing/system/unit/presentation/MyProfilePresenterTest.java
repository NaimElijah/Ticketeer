package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import ch.qos.logback.classic.Level;
import jakarta.persistence.PersistenceException;

import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.identity.application.service.MemberQueryService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.MemberDTO;
import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.ui.presenters.account.MyProfilePresenter;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.FreshGuestSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.support.ServiceErrors;
import com.ticketing.system.ui.session.UnreadAlertsSendBack;
import com.ticketing.system.notifications.application.port.in.INotificationService;

/**
 * {@link MyProfilePresenter} shows the signed-in member's profile, and only a member's.
 *
 * <ul>
 *   <li>The member is resolved through {@link SessionIdentity}, as on every member path. An admin's
 *       session stores the admin's id, and admin and member ids share one id space (ADR-012), so the
 *       stored id alone would show an admin the profile of the member with the same id.</li>
 *   <li>A failure that is not a domain rule is an internal error, and its text (SQL, constraint names,
 *       NPE details) must never reach the member (CWE-209): the failure carries the generic text.</li>
 * </ul>
 */
class MyProfilePresenterTest {

    private static final String MEMBER_TOKEN = "member-jwt";
    private static final String ADMIN_TOKEN = "admin-jwt";
    private static final int MEMBER_USER_ID = 42;
    private static final long TOKEN_EXPIRY_EPOCH_MILLIS = 0L;
    private static final MemberDTO MEMBER = new MemberDTO(MEMBER_USER_ID, "member", "member@example.com");
    private static final String INTERNAL_FAILURE_TEXT =
            "could not extract ResultSet [select u.email from users u where u.user_id=?]";

    private MemberQueryService memberQueryService;
    private AuthenticationService authenticationService;
    private MyProfilePresenter presenter;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        memberQueryService = mock(MemberQueryService.class);
        authenticationService = mock(AuthenticationService.class);
        when(authenticationService.liveMemberId(MEMBER_TOKEN)).thenReturn(OptionalInt.of(MEMBER_USER_ID));
        when(memberQueryService.getMemberProfile(MEMBER_TOKEN)).thenReturn(MEMBER);
        SessionIdentity identity = new SessionIdentity(authenticationService, new FreshGuestSession(authenticationService,
                new UnreadAlertsSendBack(mock(INotificationService.class))));
        presenter = new MyProfilePresenter(memberQueryService, identity);
    }

    @AfterEach
    void tearDown() {
        MapBackedVaadinSession.uninstall();
    }

    @Test
    void GivenAdminSessionWithAMembersId_WhenLoadProfile_ThenNotAuthenticated() {
        AuthSession.storeAuth(new AuthTokenDTO(ADMIN_TOKEN, TOKEN_EXPIRY_EPOCH_MILLIS, MEMBER_USER_ID, "admin", true));

        MyProfilePresenter.Outcome outcome = presenter.load();

        assertInstanceOf(MyProfilePresenter.Outcome.NotAuthenticated.class, outcome,
                "an admin is no member, so must not see the profile of the member with the admin's id; got " + outcome);
        verify(memberQueryService, never()).getMemberProfile(any());
    }

    @Test
    void GivenMemberSession_WhenLoadProfile_ThenTheMembersProfile() {
        AuthSession.storeAuth(new AuthTokenDTO(MEMBER_TOKEN, TOKEN_EXPIRY_EPOCH_MILLIS, MEMBER_USER_ID, "member"));

        MyProfilePresenter.Outcome.Success success =
                assertInstanceOf(MyProfilePresenter.Outcome.Success.class, presenter.load());

        assertEquals(MEMBER, success.member());
    }

    @Test
    void GivenTheProfileLookupFailsInternally_WhenLoad_ThenTheReasonIsTheGenericText() {
        AuthSession.storeAuth(new AuthTokenDTO(MEMBER_TOKEN, TOKEN_EXPIRY_EPOCH_MILLIS, MEMBER_USER_ID, "member"));
        when(memberQueryService.getMemberProfile(MEMBER_TOKEN))
                .thenThrow(new PersistenceException(INTERNAL_FAILURE_TEXT));

        MyProfilePresenter.Outcome.Failure failure =
                assertInstanceOf(MyProfilePresenter.Outcome.Failure.class, presenter.load());

        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, failure.reason());
    }

    // ---- UI-B6: a failure is logged once, with its exception (#187) ----

    @Test
    void GivenTheProfileLookupFailsInternally_WhenLoad_ThenItIsLoggedOnceWithItsException() {
        AuthSession.storeAuth(new AuthTokenDTO(MEMBER_TOKEN, TOKEN_EXPIRY_EPOCH_MILLIS, MEMBER_USER_ID, "member"));
        when(memberQueryService.getMemberProfile(MEMBER_TOKEN))
                .thenThrow(new PersistenceException(INTERNAL_FAILURE_TEXT));

        assertEquals(List.of(PersistenceException.class.getName()), throwablesLoggedAtWarnOrErrorWhileLoading());
    }

    @Test
    void GivenTheStoreIsDown_WhenLoad_ThenItIsLoggedOnceWithItsException() {
        AuthSession.storeAuth(new AuthTokenDTO(MEMBER_TOKEN, TOKEN_EXPIRY_EPOCH_MILLIS, MEMBER_USER_ID, "member"));
        when(memberQueryService.getMemberProfile(MEMBER_TOKEN))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertEquals(List.of(DataAccessResourceFailureException.class.getName()),
                throwablesLoggedAtWarnOrErrorWhileLoading());
    }

    // What the presenter and ServiceErrors log between them: one event per failure, each with its exception.
    private List<String> throwablesLoggedAtWarnOrErrorWhileLoading() {
        try (LogCapture presenterLog = LogCapture.of(MyProfilePresenter.class);
             LogCapture serviceErrorsLog = LogCapture.of(ServiceErrors.class)) {
            presenter.load();
            List<String> throwables = new ArrayList<>();
            int events = 0;
            for (LogCapture log : List.of(presenterLog, serviceErrorsLog)) {
                throwables.addAll(log.throwableClassNamesAt(Level.WARN));
                throwables.addAll(log.throwableClassNamesAt(Level.ERROR));
                events += log.messagesAt(Level.WARN).size() + log.messagesAt(Level.ERROR).size();
            }
            assertEquals(1, events, "the failure must be logged once");
            return throwables;
        }
    }
}
