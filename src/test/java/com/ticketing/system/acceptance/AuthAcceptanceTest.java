package com.ticketing.system.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import com.ticketing.system.Core.Application.dto.AuthTokenDTO;
import com.ticketing.system.Core.Application.dto.LoginDTO;
import com.ticketing.system.Core.Application.dto.GuestSessionDTO;
import com.ticketing.system.Core.Application.dto.LoginRequestDTO;
import com.ticketing.system.Core.Application.dto.LogoutRequestDTO;
import com.ticketing.system.Core.Application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.Core.Domain.ActiveOrder.ActiveOrder;
import com.ticketing.system.Core.Domain.ActiveOrder.IActiveOrderRepository;
import com.ticketing.system.shared.exception.AuthenticationFailedException;
import com.ticketing.system.shared.exception.DuplicateUsernameException;
import com.ticketing.system.shared.exception.GuestSessionRequiredException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.Session;
import com.ticketing.system.identity.domain.User;

@SpringBootTest
@ActiveProfiles("test")
class AuthAcceptanceTest {

    @Autowired
    private AuthenticationService authService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private SessionRepository sessionRepository;
    @Autowired
    private IActiveOrderRepository activeOrderRepository;

    // ------------------------------------------------------------------
    // Guest session lifecycle
    // ------------------------------------------------------------------

    @Test
    void GuestSession_StartsAndGetsRowPersisted() {
        GuestSessionDTO dto = authService.startGuestSession();

        assertNotNull(dto.sessionId());
        assertFalse(dto.sessionId().isBlank());
        Session row = sessionRepository.findById(dto.sessionId()).orElseThrow();
        assertTrue(row.isGuest());
    }

    @Test
    void GuestSession_EndDeletesRow() {
        GuestSessionDTO dto = authService.startGuestSession();
        assertTrue(sessionRepository.findById(dto.sessionId()).isPresent());

        authService.endGuestSession(dto.sessionId());

        assertFalse(sessionRepository.findById(dto.sessionId()).isPresent());
    }

    // ------------------------------------------------------------------
    // UC-11 — Register requires Guest session (D10a)
    // ------------------------------------------------------------------

    @Test
    void GivenUniqueDetails_WhenRegister_ThenMemberCreated() {
        String sid = authService.startGuestSession().sessionId();

        authService.register(new RegisterRequestDTO("alice", "alice@example.com", "Password1", sid, 20));

        assertTrue(userRepository.existsByUsername("alice"));
        User stored = userRepository.findByUsername("alice").orElseThrow();
        assertEquals("alice", stored.getUsername());
        assertEquals("alice@example.com", stored.getEmail());
    }

    @Test
    void GivenSuccessfulRegistration_WhenCheckSession_ThenStillGuest() {
        String sid = authService.startGuestSession().sessionId();

        authService.register(new RegisterRequestDTO("carol", "carol@example.com", "Password1", sid, 20));

        // II.1.4 / D10a: session must still be Guest after register.
        Session row = sessionRepository.findById(sid).orElseThrow();
        assertTrue(row.isGuest());
        // User was actually created.
        assertTrue(userRepository.existsByUsername("carol"));
    }

    @Test
    void GivenDuplicateUsername_WhenRegister_ThenRejected() {
        String sid1 = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO("bob", "bob@example.com", "Password1", sid1, 20));

        String sid2 = authService.startGuestSession().sessionId();
        assertThrows(DuplicateUsernameException.class,
                () -> authService.register(new RegisterRequestDTO("bob", "bob2@example.com", "Password1", sid2, 20)));
    }

    @Test
    void GivenNoGuestSession_WhenRegister_ThenGuestSessionRequired() {
        assertThrows(GuestSessionRequiredException.class,
                () -> authService.register(new RegisterRequestDTO("nobody", "nobody@example.com", "Password1", null, 20)));
        assertFalse(userRepository.existsByUsername("nobody"));
    }

    // ------------------------------------------------------------------
    // UC-12 — Login promotes Guest session in place
    // ------------------------------------------------------------------

    @Test
    @Disabled("Will fix later")
    void GivenValidCredentials_WhenLogin_ThenTokenIssued() {
        authService.register(new RegisterRequestDTO("dave", "dave@example.com", "Password1", null, 20));
        LoginDTO result = authService
                .login(new LoginRequestDTO("dave", "Password1", authService.startGuestSession().sessionId()));
        AuthTokenDTO tokenResult = result.authToken();

        assertNotNull(tokenResult.token());
        assertFalse(tokenResult.token().isBlank());
        assertEquals("dave", tokenResult.username());
        assertTrue(authService.validateToken(tokenResult.token()));
        assertEquals(tokenResult.userId(), authService.extractUserId(tokenResult.token()));
        assertTrue(tokenResult.expiresAtEpochMillis() > System.currentTimeMillis());
    }

    @Test
    void GivenLogin_ThenGuestSessionPromotedInPlace_SidPreserved() {
        String sid = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO("hank", "hank@example.com", "Password1", sid, 20));

        LoginDTO resultLogin = authService.login(new LoginRequestDTO("hank", "Password1", sid));
        AuthTokenDTO result = resultLogin.authToken();
        // Same Session row — userId is now set, sessionId preserved.
        Session row = sessionRepository.findById(sid).orElseThrow();
        assertTrue(row.isMember());
        assertEquals(result.userId(), row.getUserId());
        assertEquals(sid, row.getSessionId());
    }

    @Test
    void GivenInvalidCredentials_WhenLogin_ThenGenericReject() {
        String sid = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO("eve", "eve@example.com", "Password1", sid, 20));

        // Wrong password and unknown user yield the SAME exception — no enumeration
        // leak.
        // Need a fresh guest session each time because the previous would be promoted
        // on success.
        String sidA = authService.startGuestSession().sessionId();
        assertThrows(AuthenticationFailedException.class,
                () -> authService.login(new LoginRequestDTO("eve", "wrongpass", sidA)));
        String sidB = authService.startGuestSession().sessionId();
        assertThrows(AuthenticationFailedException.class,
                () -> authService.login(new LoginRequestDTO("nosuchuser", "anything1", sidB)));
    }

    @Test
    void GivenNoGuestSession_WhenLogin_ThenGuestSessionRequired() {
        assertThrows(GuestSessionRequiredException.class,
                () -> authService.login(new LoginRequestDTO("alice", "Password1", null)));
    }

    @Test
    void GivenLoginWithMemberSessionId_WhenLogin_ThenGuestSessionRequired() {
        // Promoting twice on the same sid should be rejected — second login must use a
        // fresh Guest.
        String sid = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO("ivy", "ivy@example.com", "Password1", sid, 20));
        authService.login(new LoginRequestDTO("ivy", "Password1", sid)); // first promotion succeeds

        assertThrows(GuestSessionRequiredException.class,
                () -> authService.login(new LoginRequestDTO("ivy", "Password1", sid)));
    }

    // UC-13 / D9a — Member cart restored on next login
    @Test
    void GivenMemberWithPendingOrder_WhenLogin_ThenOrderRestored() {
        // Set up: a Member with a cart attached to an old (now-gone) session.
        // Simulates: user logged in once, added items, logged out, comes back later.
        String firstSid = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO("rest1", "rest1@example.com", "Password1", firstSid, 20));
        LoginDTO first = authService.login(new LoginRequestDTO("rest1", "Password1", firstSid));

        // Simulate an active cart on the first session.
        ActiveOrder cart = ActiveOrder.forMember(first.authToken().userId(), firstSid);
        cart.addStandingReservation(1, 10, 1, 50.0, LocalDateTime.now());
        activeOrderRepository.save(cart);

        // Logout — D8 (L1) deletes the Session row; D9a preserves the cart.
        authService.logout(new LogoutRequestDTO(first.authToken().token()));

        // New visit: fresh Guest session, then login.
        String secondSid = authService.startGuestSession().sessionId();
        LoginDTO second = authService.login(new LoginRequestDTO("rest1", "Password1", secondSid));

        // The cart was restored: its sessionId now points to the new session.
        ActiveOrder restored = activeOrderRepository.getByUserId(second.authToken().userId());
        assertNotNull(restored);
        assertEquals(secondSid, restored.getSessionId());
        assertEquals(1, restored.getItems().size());
    }

    @Test
    @Disabled("UC-13 alt: expired order is not restored (Phase 5 sweeper)")
    void GivenExpiredOrder_WhenLogin_ThenNoRestoration() {
    }

    // ------------------------------------------------------------------
    // UC-14 — Logout (D8 = L1: session row deleted)
    // ------------------------------------------------------------------

    @Test
    void GivenLoggedInMember_WhenLogout_ThenStateGuest() {
        // II.3.1 / D8: logout deletes the Session row entirely.
        String sid = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO("frank", "frank@example.com", "Password1", sid, 20  ));
        LoginDTO session = authService.login(new LoginRequestDTO("frank", "Password1", sid));
        assertTrue(authService.validateToken(session.authToken().token()));

        authService.logout(new LogoutRequestDTO(session.authToken().token()));

        assertThrows(InvalidTokenException.class, () -> authService.validateToken(session.authToken().token()));
        // Session row gone — user must startGuestSession() to act again (D8 = L1).
        assertFalse(sessionRepository.findById(sid).isPresent());
    }

    @Test
    void GivenAlreadyLoggedOutToken_WhenLogoutAgain_ThenNoError() {
        // Idempotency: repeating logout with the same (already-revoked) token must not
        // throw.
        String sid = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO("grace", "grace@example.com", "Password1", sid, 20));
        LoginDTO session = authService.login(new LoginRequestDTO("grace", "Password1", sid));
        authService.logout(new LogoutRequestDTO(session.authToken().token()));

        // Second call should silently succeed.
        authService.logout(new LogoutRequestDTO(session.authToken().token()));
    }

    @Test
    void DifferentGuestSessionsHaveDifferentIds() {
        String sidA = authService.startGuestSession().sessionId();
        String sidB = authService.startGuestSession().sessionId();
        assertNotEquals(sidA, sidB);
    }

    @Test
    void GivenLoggedInWithOrder_WhenLogout_ThenOrderStaysLinked() {
        // II.3.0.1 / D9a: logout deletes the Session row but the cart with
        // userId set survives.
        String sid = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO("link1", "link1@example.com", "Password1", sid, 20));
        LoginDTO session = authService.login(new LoginRequestDTO("link1", "Password1", sid));

        ActiveOrder cart = ActiveOrder.forMember(session.authToken().userId(), sid);
        cart.addStandingReservation(2, 20, 2, 30.0, LocalDateTime.now());
        activeOrderRepository.save(cart);

        authService.logout(new LogoutRequestDTO(session.authToken().token()));

        // Session row gone; cart row preserved with its userId.
        assertFalse(sessionRepository.findById(sid).isPresent());
        ActiveOrder preserved = activeOrderRepository.getByUserId(session.authToken().userId());
        assertNotNull(preserved);
        assertEquals(2, preserved.getItems().size());
    }
}
