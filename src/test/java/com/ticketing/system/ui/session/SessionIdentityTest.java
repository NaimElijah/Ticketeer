package com.ticketing.system.ui.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import com.vaadin.flow.server.VaadinSession;

/**
 * {@link SessionIdentity}'s contract (class Javadoc): a member is a visitor whose token still
 * validates, and everyone else, including a member whose token has expired, is a guest using the
 * guest session id. Token validation reports an expired token with {@link SessionExpiredException}
 * and a revoked one with {@link InvalidTokenException}, so each method must turn those into the
 * guest answer rather than pass them on to views and presenters.
 */
class SessionIdentityTest {

    private static final String MEMBER_TOKEN = "member-jwt";
    private static final String GUEST_SESSION_ID = "guest-sid";
    private static final int MEMBER_USER_ID = 42;
    private static final int NO_MEMBER = 0;

    // The attribute keys AuthSession and GuestSession read from the Vaadin session.
    private static final String TOKEN_KEY = "authSession.token";
    private static final String GUEST_SESSION_KEY = "guestSession.sessionId";

    @BeforeEach
    void signedInVisitorWithAGuestSession() {
        VaadinSession session = mock(VaadinSession.class);
        when(session.getAttribute(TOKEN_KEY)).thenReturn(MEMBER_TOKEN);
        when(session.getAttribute(GUEST_SESSION_KEY)).thenReturn(GUEST_SESSION_ID);
        VaadinSession.setCurrent(session);
    }

    @AfterEach
    void clearVaadinSession() {
        VaadinSession.setCurrent(null);
    }

    static Stream<Arguments> rejectedTokens() {
        return Stream.of(
                Arguments.of("expired", new SessionExpiredException()),
                Arguments.of("revoked", new InvalidTokenException("revoked")));
    }

    // Guards the session set-up: without it every guest assertion below would pass vacuously.
    @Test
    void GivenAValidMemberToken_WhenAsked_ThenTheVisitorIsThatMember() {
        SessionIdentity identity = identityWhoseTokenValidates();

        assertTrue(identity.isMember());
        assertEquals(MEMBER_TOKEN, identity.memberToken());
        assertEquals(MEMBER_USER_ID, identity.memberUserId());
        assertEquals(MEMBER_TOKEN, identity.credential());
    }

    @ParameterizedTest(name = "{0} token")
    @MethodSource("rejectedTokens")
    void GivenARejectedMemberToken_WhenAskedIsMember_ThenFalse(String reason, RuntimeException rejection) {
        SessionIdentity identity = identityWhoseTokenIsRejectedWith(rejection);

        boolean member = assertDoesNotThrow(identity::isMember, "isMember() must not throw for a token that is " + reason);
        assertFalse(member, "a visitor whose token is " + reason + " is a guest");
    }

    @ParameterizedTest(name = "{0} token")
    @MethodSource("rejectedTokens")
    void GivenARejectedMemberToken_WhenAskedMemberToken_ThenNull(String reason, RuntimeException rejection) {
        SessionIdentity identity = identityWhoseTokenIsRejectedWith(rejection);

        String token = assertDoesNotThrow(identity::memberToken,
                "memberToken() must not throw for a token that is " + reason);
        assertNull(token, "a guest has no member token");
    }

    @ParameterizedTest(name = "{0} token")
    @MethodSource("rejectedTokens")
    void GivenARejectedMemberToken_WhenAskedMemberUserId_ThenNoMember(String reason, RuntimeException rejection) {
        SessionIdentity identity = identityWhoseTokenIsRejectedWith(rejection);

        int userId = assertDoesNotThrow(identity::memberUserId,
                "memberUserId() must not throw for a token that is " + reason);
        assertEquals(NO_MEMBER, userId, "a guest has no member user id");
    }

    @ParameterizedTest(name = "{0} token")
    @MethodSource("rejectedTokens")
    void GivenARejectedMemberToken_WhenAskedCredential_ThenTheGuestSessionId(String reason, RuntimeException rejection) {
        SessionIdentity identity = identityWhoseTokenIsRejectedWith(rejection);

        String credential = assertDoesNotThrow(identity::credential,
                "credential() must not throw for a token that is " + reason);
        assertEquals(GUEST_SESSION_ID, credential, "a guest's credential is its guest session id");
    }

    // The only two places that know how SessionIdentity checks a token, so swapping its dependency
    // (identity's AuthenticationService) touches these and nothing else.
    private static SessionIdentity identityWhoseTokenValidates() {
        AuthenticationService authenticationService = mock(AuthenticationService.class);
        when(authenticationService.validateToken(MEMBER_TOKEN)).thenReturn(true);
        when(authenticationService.extractUserId(MEMBER_TOKEN)).thenReturn(MEMBER_USER_ID);
        return new SessionIdentity(authenticationService);
    }

    private static SessionIdentity identityWhoseTokenIsRejectedWith(RuntimeException rejection) {
        AuthenticationService authenticationService = mock(AuthenticationService.class);
        when(authenticationService.validateToken(MEMBER_TOKEN)).thenThrow(rejection);
        return new SessionIdentity(authenticationService);
    }
}
