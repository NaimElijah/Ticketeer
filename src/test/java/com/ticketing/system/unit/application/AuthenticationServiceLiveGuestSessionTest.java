package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.identity.adapter.out.persistence.MemorySessionRepository;
import com.ticketing.system.identity.adapter.out.security.JwtSessionManager;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.identity.application.port.out.CartRestorationPort;
import com.ticketing.system.identity.application.port.out.PasswordHasher;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.identity.config.IdentityProperties;
import com.ticketing.system.identity.config.LockoutProperties;
import com.ticketing.system.identity.config.SessionProperties;
import com.ticketing.system.notifications.application.service.NotificationDispatchService;
import com.ticketing.system.shared.metrics.ISystemMetrics;
import com.ticketing.system.testutil.AdjustableClock;

/**
 * {@link AuthenticationService#isLiveGuestSession(String)} is the read-only question the UI asks
 * before using its stored guest session id: is it still a live guest session? A guest session dies
 * at its idle timeout, and the raw id of a member or admin session is never a guest credential
 * (ADR-012). The answer is a plain boolean; none of these cases throws.
 *
 * <p>Runs on the real {@link JwtSessionManager} over an in-memory session store, so the member and
 * admin session rows are the ones sign-in creates.
 */
class AuthenticationServiceLiveGuestSessionTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final long MEMBER_TTL_MINUTES = 1440;
    private static final long GUEST_IDLE_MINUTES = 30;
    // At least 256 bits, as JwtSessionManager requires of its HMAC secret.
    private static final String JWT_TEST_SECRET =
            "unit-test-secret-must-be-long-enough-for-hmac-sha-256-please-ignore-me";
    private static final int USER_ID = 5;

    private AdjustableClock clock;
    private JwtSessionManager sessionManager;
    private AuthenticationService service;

    @BeforeEach
    void setUp() {
        clock = new AdjustableClock(T0);
        SessionProperties sessionProperties = new SessionProperties(MEMBER_TTL_MINUTES, GUEST_IDLE_MINUTES);
        MemorySessionRepository sessions = new MemorySessionRepository(clock);
        sessionManager = new JwtSessionManager(JWT_TEST_SECRET, sessionProperties, sessions, clock);
        service = new AuthenticationService(
                mock(UserRepository.class), mock(PasswordHasher.class), sessionManager,
                mock(CartRestorationPort.class), mock(NotificationDispatchService.class),
                sessions, mock(ISystemMetrics.class), clock, mock(AdminRepository.class),
                sessionProperties, new LockoutProperties(5, 15L),
                new IdentityProperties(List.of(), "admin"));
    }

    @Test
    void GivenAJustStartedGuestSession_WhenIsLiveGuestSession_ThenTrue() {
        String guestSessionId = service.startGuestSession().sessionId();

        assertTrue(service.isLiveGuestSession(guestSessionId));
    }

    @Test
    void GivenAGuestSessionOneMinuteBeforeItsIdleTimeout_WhenIsLiveGuestSession_ThenTrue() {
        String guestSessionId = service.startGuestSession().sessionId();
        clock.advance(Duration.ofMinutes(GUEST_IDLE_MINUTES - 1));

        assertTrue(service.isLiveGuestSession(guestSessionId));
    }

    @Test
    void GivenAGuestSessionAtItsIdleTimeout_WhenIsLiveGuestSession_ThenFalse() {
        // The expiry boundary is inclusive (Session.isExpiredAt).
        String guestSessionId = service.startGuestSession().sessionId();
        clock.advance(Duration.ofMinutes(GUEST_IDLE_MINUTES));

        assertFalse(service.isLiveGuestSession(guestSessionId));
    }

    @Test
    void GivenAnEndedGuestSession_WhenIsLiveGuestSession_ThenFalse() {
        String guestSessionId = service.startGuestSession().sessionId();
        service.endGuestSession(guestSessionId);

        assertFalse(service.isLiveGuestSession(guestSessionId));
    }

    @Test
    void GivenAMemberSessionId_WhenIsLiveGuestSession_ThenFalse() {
        String memberToken = sessionManager.generateToken(USER_ID, "member");
        String memberSessionId = sessionManager.extractSessionId(memberToken);

        assertFalse(service.isLiveGuestSession(memberSessionId), "a member's session is not a guest session");
    }

    @Test
    void GivenAnAdminSessionId_WhenIsLiveGuestSession_ThenFalse() {
        String adminToken = sessionManager.generateAdminToken(USER_ID, "admin");
        String adminSessionId = sessionManager.extractSessionId(adminToken);

        assertFalse(service.isLiveGuestSession(adminSessionId), "an admin's session is not a guest session");
    }

    @Test
    void GivenAnUnknownSessionId_WhenIsLiveGuestSession_ThenFalse() {
        assertFalse(service.isLiveGuestSession("no-such-session"));
    }

    @Test
    void GivenNoSessionId_WhenIsLiveGuestSession_ThenFalse() {
        assertFalse(service.isLiveGuestSession(null));
        assertFalse(service.isLiveGuestSession(" "));
    }
}
