package com.ticketing.system.ui.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.GuestSessionDTO;
import com.ticketing.system.shared.dto.NotificationDTO;

/**
 * {@link FreshGuestSession#restore()} restores "every Vaadin session has a fresh guest session id"
 * after the member is gone: it drops the member's session state (auth, current company, notification
 * inbox) and pins a newly minted guest session. These are the steps {@code SignOutFlow} takes after
 * logout, and {@code SessionIdentity} takes when a stored token is no longer live.
 */
class FreshGuestSessionTest {

    private static final String MEMBER_TOKEN = "member-jwt";
    private static final int MEMBER_USER_ID = 42;
    private static final String MEMBER_SESSION_ID = "member-sid";
    private static final String FRESH_GUEST_SESSION_ID = "fresh-guest-sid";
    private static final int COMPANY_ID = 7;
    private static final LocalDateTime NOTIFIED_AT = LocalDateTime.of(2027, 1, 1, 12, 0);

    private AuthenticationService authenticationService;
    private FreshGuestSession freshGuestSession;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        authenticationService = mock(AuthenticationService.class);
        freshGuestSession = new FreshGuestSession(authenticationService);
        AuthSession.storeAuth(new AuthTokenDTO(MEMBER_TOKEN, 0L, MEMBER_USER_ID, "member"));
        GuestSession.setSessionId(MEMBER_SESSION_ID);
        CurrentCompanies.setCurrentCompany(COMPANY_ID);
        NotificationSession.store(List.of(new NotificationDTO("n-1", "PURCHASE_CONFIRMED", "UNREAD", "hi", NOTIFIED_AT)));
    }

    @AfterEach
    void tearDown() {
        MapBackedVaadinSession.uninstall();
    }

    @Test
    void GivenASignedInMember_WhenRestored_ThenTheMembersSessionStateIsDropped() {
        when(authenticationService.startGuestSession())
                .thenReturn(new GuestSessionDTO(FRESH_GUEST_SESSION_ID, Instant.EPOCH));

        freshGuestSession.restore();

        assertNull(AuthSession.token(), "the member's token is dropped");
        assertFalse(AuthSession.isSignedIn(), "the visitor is no longer signed in");
        assertNull(CurrentCompanies.currentCompanyId(), "the member's current company is dropped");
        assertEquals(List.of(), NotificationSession.getAll(), "the member's notification inbox is dropped");
    }

    @Test
    void GivenASignedInMember_WhenRestored_ThenAFreshGuestSessionIsPinned() {
        when(authenticationService.startGuestSession())
                .thenReturn(new GuestSessionDTO(FRESH_GUEST_SESSION_ID, Instant.EPOCH));

        freshGuestSession.restore();

        assertEquals(FRESH_GUEST_SESSION_ID, GuestSession.sessionId(),
                "the guest session is a newly minted one, not the member's " + MEMBER_SESSION_ID);
    }

    @Test
    void GivenTheGuestSessionCannotBeStarted_WhenRestored_ThenTheOldSessionIdIsClearedWithoutThrowing() {
        when(authenticationService.startGuestSession()).thenThrow(new IllegalStateException("session store down"));

        assertDoesNotThrow(freshGuestSession::restore, "a failed guest-session start must not reach the view");

        assertNull(GuestSession.sessionId(), "the member's dead session id must not stay pinned");
        assertNull(AuthSession.token(), "the member's token is dropped even when no guest session starts");
    }

    // ---- sign-out leaves nothing of the previous visitor -------------------------------------

    @Test
    void GivenRecentSearches_WhenRestored_ThenTheyAreCleared() {
        RecentSearches.add("jazz night");
        when(authenticationService.startGuestSession())
                .thenReturn(new GuestSessionDTO(FRESH_GUEST_SESSION_ID, Instant.EPOCH));

        freshGuestSession.restore();

        assertEquals(List.of(), RecentSearches.get(), "the previous visitor's recent searches must be dropped");
    }

    // ---- renewing only the guest session ---------------------------------------------------
    // renewGuest() replaces a dead guest session id and nothing else: it must not sign anyone out
    // (an admin on the storefront is a guest there, ADR-012).

    private static final String ADMIN_TOKEN = "admin-jwt";
    private static final int ADMIN_USER_ID = 1;
    private static final String DEAD_GUEST_SESSION_ID = "dead-guest-sid";

    @Test
    void GivenASignedInAdminWithADeadGuestSession_WhenGuestRenewed_ThenOnlyTheGuestSessionIdIsReplaced() {
        AuthSession.storeAuth(new AuthTokenDTO(ADMIN_TOKEN, 0L, ADMIN_USER_ID, "admin", true));
        GuestSession.setSessionId(DEAD_GUEST_SESSION_ID);
        RecentSearches.add("jazz night");
        when(authenticationService.startGuestSession())
                .thenReturn(new GuestSessionDTO(FRESH_GUEST_SESSION_ID, Instant.EPOCH));

        freshGuestSession.renewGuest();

        assertEquals(FRESH_GUEST_SESSION_ID, GuestSession.sessionId(), "a fresh guest session is pinned");
        assertEquals(ADMIN_TOKEN, AuthSession.token(), "the admin's token stays");
        assertTrue(AuthSession.isAdmin(), "the admin stays signed in as an admin");
        assertEquals(COMPANY_ID, CurrentCompanies.currentCompanyId(), "the current company stays");
        assertEquals(List.of("jazz night"), RecentSearches.get(), "the recent searches stay");
    }
}
