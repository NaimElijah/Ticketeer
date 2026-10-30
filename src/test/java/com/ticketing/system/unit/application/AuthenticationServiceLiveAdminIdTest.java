package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

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
import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.notifications.application.service.NotificationDispatchService;
import com.ticketing.system.shared.metrics.ISystemMetrics;
import com.ticketing.system.testutil.AdjustableClock;

/**
 * {@link AuthenticationService#liveAdminId(String)} answers "is this stored admin token still live,
 * and whose is it?" for the UI's navigation guard, without throwing for the expected no: an expired,
 * revoked or malformed token, or a member token (ADR-012: admin readers never accept member tokens).
 * It is the admin counterpart of {@link AuthenticationService#liveMemberId(String)}.
 *
 * <p>Runs on the real {@link JwtSessionManager} over an in-memory session store, so "expired",
 * "revoked" and "a member token" are the real ones, not stubbed answers.
 */
class AuthenticationServiceLiveAdminIdTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final long MEMBER_TTL_MINUTES = 60;
    private static final long GUEST_IDLE_MINUTES = 30;
    // At least 256 bits, as JwtSessionManager requires of its HMAC secret.
    private static final String JWT_TEST_SECRET =
            "unit-test-secret-must-be-long-enough-for-hmac-sha-256-please-ignore-me";
    // Admin and member ids share one id space; the default admin is id 1, and so is the first member.
    private static final int ADMIN_ID = 1;

    private AdjustableClock clock;
    private JwtSessionManager sessionManager;
    private AuthenticationService service;

    @BeforeEach
    void setUp() {
        clock = new AdjustableClock(T0);
        SessionProperties sessionProperties = new SessionProperties(MEMBER_TTL_MINUTES, GUEST_IDLE_MINUTES);
        MemorySessionRepository sessions = new MemorySessionRepository(clock);
        sessionManager = new JwtSessionManager(JWT_TEST_SECRET, sessionProperties, sessions, clock);
        AdminRepository admins = mock(AdminRepository.class);
        when(admins.findById(ADMIN_ID)).thenReturn(new Admin(ADMIN_ID, "admin", "hash", true));
        service = new AuthenticationService(
                mock(UserRepository.class), mock(PasswordHasher.class), sessionManager,
                mock(CartRestorationPort.class), mock(NotificationDispatchService.class),
                sessions, mock(ISystemMetrics.class), clock, admins,
                sessionProperties, new LockoutProperties(5, 15L),
                new IdentityProperties(List.of(), "admin"));
    }

    @Test
    void GivenALiveAdminToken_WhenLiveAdminId_ThenTheAdminsId() {
        String adminToken = sessionManager.generateAdminToken(ADMIN_ID, "admin");

        assertEquals(OptionalInt.of(ADMIN_ID), service.liveAdminId(adminToken));
    }

    @Test
    void GivenAMemberTokenWithTheAdminsId_WhenLiveAdminId_ThenEmptyWithoutThrowing() {
        String memberToken = sessionManager.generateToken(ADMIN_ID, "member");

        OptionalInt adminId = assertDoesNotThrow(() -> service.liveAdminId(memberToken));

        assertEquals(OptionalInt.empty(), adminId, "a member token names no admin, even with the admin's id");
    }

    @Test
    void GivenAnExpiredAdminToken_WhenLiveAdminId_ThenEmptyWithoutThrowing() {
        String adminToken = sessionManager.generateAdminToken(ADMIN_ID, "admin");
        clock.advance(Duration.ofMinutes(MEMBER_TTL_MINUTES + 1));

        OptionalInt adminId = assertDoesNotThrow(() -> service.liveAdminId(adminToken));

        assertEquals(OptionalInt.empty(), adminId);
    }

    @Test
    void GivenARevokedAdminToken_WhenLiveAdminId_ThenEmptyWithoutThrowing() {
        String adminToken = sessionManager.generateAdminToken(ADMIN_ID, "admin");
        sessionManager.invalidate(adminToken);

        OptionalInt adminId = assertDoesNotThrow(() -> service.liveAdminId(adminToken));

        assertEquals(OptionalInt.empty(), adminId);
    }

    @Test
    void GivenAMalformedToken_WhenLiveAdminId_ThenEmptyWithoutThrowing() {
        OptionalInt adminId = assertDoesNotThrow(() -> service.liveAdminId("not.a.jwt"));

        assertEquals(OptionalInt.empty(), adminId);
    }

    @Test
    void GivenNoToken_WhenLiveAdminId_ThenEmptyWithoutThrowing() {
        OptionalInt adminId = assertDoesNotThrow(() -> service.liveAdminId(null));

        assertEquals(OptionalInt.empty(), adminId);
    }
}
