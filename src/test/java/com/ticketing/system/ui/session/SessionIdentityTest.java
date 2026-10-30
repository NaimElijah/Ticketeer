package com.ticketing.system.ui.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.OptionalInt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.GuestSessionDTO;

/**
 * {@link SessionIdentity}'s contract (class Javadoc): a member is a visitor whose token is still live;
 * everyone else is a guest. A member whose token has expired or been revoked must become a guest with
 * a <em>fresh</em> guest session: at login the guest session was promoted in place, so the stored
 * guest session id is the member's own dead session, and falling back to it would reach the member's
 * cart on guest paths and be refused by catalog browsing.
 */
class SessionIdentityTest {

    private static final String MEMBER_TOKEN = "member-jwt";
    private static final int MEMBER_USER_ID = 42;
    private static final String MEMBER_NAME = "member";
    private static final long TOKEN_EXPIRY_EPOCH_MILLIS = 0L;
    // At login the guest session is promoted in place, so the member's sid is what GuestSession holds.
    private static final String MEMBER_SESSION_ID = "member-sid";
    private static final String GUEST_SESSION_ID = "guest-sid";
    private static final String FRESH_GUEST_SESSION_ID = "fresh-guest-sid";
    private static final int NO_MEMBER = 0;
    private static final String ADMIN_TOKEN = "admin-jwt";
    // Member and admin ids share one id space; the default admin is id 1.
    private static final int ADMIN_USER_ID = 1;
    private static final String ADMIN_NAME = "admin";

    private AuthenticationService authenticationService;
    private FreshGuestSession freshGuestSession;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        authenticationService = mock(AuthenticationService.class);
        when(authenticationService.startGuestSession())
                .thenReturn(new GuestSessionDTO(FRESH_GUEST_SESSION_ID, Instant.EPOCH));
        freshGuestSession = spy(new FreshGuestSession(authenticationService));
    }

    @AfterEach
    void tearDown() {
        MapBackedVaadinSession.uninstall();
    }

    // ---- a stored token that is no longer live ----------------------------------------------

    @Test
    void GivenARejectedStoredToken_WhenAskedIsMember_ThenTheVisitorIsAGuest() {
        SessionIdentity identity = identityForAMemberWhoseTokenIsRejected();

        assertFalse(identity.isMember(), "a visitor whose token is no longer live is a guest");
    }

    @Test
    void GivenARejectedStoredToken_WhenAskedMemberToken_ThenNull() {
        SessionIdentity identity = identityForAMemberWhoseTokenIsRejected();

        assertNull(identity.memberToken(), "a guest has no member token");
    }

    @Test
    void GivenARejectedStoredToken_WhenAskedMemberUserId_ThenNoMember() {
        SessionIdentity identity = identityForAMemberWhoseTokenIsRejected();

        assertEquals(NO_MEMBER, identity.memberUserId(), "a guest has no member user id");
    }

    @Test
    void GivenARejectedStoredToken_WhenAskedCredential_ThenAFreshGuestSessionIdNotTheMembersOwn() {
        SessionIdentity identity = identityForAMemberWhoseTokenIsRejected();

        assertEquals(FRESH_GUEST_SESSION_ID, identity.credential(),
                "the guest credential must be a fresh guest session, not the member's dead " + MEMBER_SESSION_ID);
    }

    @Test
    void GivenARejectedStoredToken_WhenAskedGuestSessionId_ThenAFreshGuestSessionIdNotTheMembersOwn() {
        SessionIdentity identity = identityForAMemberWhoseTokenIsRejected();

        assertEquals(FRESH_GUEST_SESSION_ID, identity.guestSessionId(),
                "the guest session must be a fresh one, not the member's dead " + MEMBER_SESSION_ID);
    }

    @Test
    void GivenARejectedStoredToken_WhenEveryQuestionIsAsked_ThenTheGuestSessionIsRestoredOnce() {
        SessionIdentity identity = identityForAMemberWhoseTokenIsRejected();

        identity.isMember();
        identity.memberToken();
        identity.memberUserId();
        identity.credential();
        identity.guestSessionId();

        verify(freshGuestSession, times(1)).restore();
    }

    // ---- no stored token ----------------------------------------------------------------------

    @Test
    void GivenNoStoredToken_WhenAsked_ThenAGuestOnItsOwnSessionWithoutARestore() {
        GuestSession.setSessionId(GUEST_SESSION_ID);
        SessionIdentity identity = identity();

        assertFalse(identity.isMember());
        assertNull(identity.memberToken());
        assertEquals(NO_MEMBER, identity.memberUserId());
        assertEquals(GUEST_SESSION_ID, identity.credential());
        assertEquals(GUEST_SESSION_ID, identity.guestSessionId());
        verify(freshGuestSession, never()).restore();
    }

    // ---- a live stored token ------------------------------------------------------------------

    @Test
    void GivenALiveStoredToken_WhenAsked_ThenTheMemberWithoutARestore() {
        storeSignedInMember();
        when(authenticationService.liveMemberId(MEMBER_TOKEN)).thenReturn(OptionalInt.of(MEMBER_USER_ID));
        SessionIdentity identity = identity();

        assertTrue(identity.isMember());
        assertEquals(MEMBER_TOKEN, identity.memberToken());
        assertEquals(MEMBER_USER_ID, identity.memberUserId());
        assertEquals(MEMBER_TOKEN, identity.credential());
        verify(freshGuestSession, never()).restore();
    }

    @Test
    void GivenALiveStoredToken_WhenAskedMemberUserId_ThenItComesFromOneLiveMemberIdCall() {
        storeSignedInMember();
        when(authenticationService.liveMemberId(MEMBER_TOKEN)).thenReturn(OptionalInt.of(MEMBER_USER_ID));
        SessionIdentity identity = identity();

        assertEquals(MEMBER_USER_ID, identity.memberUserId());
        verify(authenticationService, times(1)).liveMemberId(MEMBER_TOKEN);
        verify(authenticationService, never()).extractUserId(anyString());
    }

    // ---- a stored admin token ---------------------------------------------------------------
    // An admin is not a member, and not a member whose token died either: answering "not a member"
    // must not sign the admin out by restoring a fresh guest session (#185).

    @Test
    void GivenAStoredAdminToken_WhenEveryQuestionIsAsked_ThenNotAMemberAndTheGuestSessionIsNeverRestored() {
        storeSignedInAdmin();
        when(authenticationService.liveMemberId(ADMIN_TOKEN)).thenReturn(OptionalInt.empty());
        SessionIdentity identity = identity();

        assertFalse(identity.isMember(), "an admin is not a member");
        identity.memberToken();
        identity.memberUserId();
        identity.credential();
        identity.guestSessionId();

        verify(freshGuestSession, never()).restore();
        assertEquals(ADMIN_TOKEN, AuthSession.token(), "the admin's sign-in must survive member-identity checks");
    }

    // ---- arrangement --------------------------------------------------------------------------

    // The one place that knows SessionIdentity's dependencies, so a change to them touches only this.
    private SessionIdentity identity() {
        return new SessionIdentity(authenticationService, freshGuestSession);
    }

    private SessionIdentity identityForAMemberWhoseTokenIsRejected() {
        storeSignedInMember();
        when(authenticationService.liveMemberId(MEMBER_TOKEN)).thenReturn(OptionalInt.empty());
        return identity();
    }

    private static void storeSignedInAdmin() {
        AuthSession.storeAuth(new AuthTokenDTO(ADMIN_TOKEN, TOKEN_EXPIRY_EPOCH_MILLIS, ADMIN_USER_ID, ADMIN_NAME, true));
        GuestSession.setSessionId(GUEST_SESSION_ID);
    }

    private static void storeSignedInMember() {
        AuthSession.storeAuth(new AuthTokenDTO(MEMBER_TOKEN, TOKEN_EXPIRY_EPOCH_MILLIS, MEMBER_USER_ID, MEMBER_NAME));
        GuestSession.setSessionId(MEMBER_SESSION_ID);
    }
}
