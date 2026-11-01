package com.ticketing.system.unit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.ticketing.system.bootstrap.CommittedAdminPasswordRotation;
import com.ticketing.system.bootstrap.CommittedDefaultSecretsGuard;
import com.ticketing.system.governance.config.PlatformAdminProperties;
import com.ticketing.system.identity.adapter.out.persistence.MemoryAdminRepository;
import com.ticketing.system.identity.adapter.out.persistence.MemorySessionRepository;
import com.ticketing.system.identity.adapter.out.security.BcryptPasswordHasher;
import com.ticketing.system.identity.adapter.out.security.JwtSessionManager;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.identity.application.port.out.PasswordHasher;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.config.JwtProperties;
import com.ticketing.system.identity.config.SessionProperties;
import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.shared.exception.InvalidTokenException;

/**
 * When {@link CommittedAdminPasswordRotation} gives the default admin the configured password, the
 * admin sessions issued under the previous password end with it, and the rotation is done while the
 * application context starts, before any runner and before the web server takes requests (#187).
 *
 * <p>Each test starts a plain application context holding the rotation and its collaborators, with
 * no active profile, so the rotation applies. Starting the context runs no {@code ApplicationRunner}:
 * whatever the rotation has done once the context is up, it did during startup. Tokens are real
 * {@link JwtSessionManager} tokens over an in-memory session store, so "no longer valid" is the real
 * revocation check.
 */
class CommittedAdminPasswordRotationSessionsTest {

    // ---- Defect 3: the rotation ends the default admin's sessions, during startup (#187) ----

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final long MEMBER_TTL_MINUTES = 60;
    private static final long GUEST_IDLE_MINUTES = 30;
    // At least 256 bits, as JwtSessionManager requires of its HMAC secret.
    private static final String JWT_TEST_SECRET =
            "unit-test-secret-must-be-long-enough-for-hmac-sha-256-please-ignore-me";
    private static final int DEFAULT_ADMIN_ID = 1;
    private static final int OTHER_ADMIN_ID = 7;
    // Admin and member ids share one id space (ADR-012): this member has the default admin's id.
    private static final int MEMBER_WITH_THE_ADMINS_ID = DEFAULT_ADMIN_ID;
    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String OTHER_ADMIN_USERNAME = "ops.admin";
    private static final String MEMBER_USERNAME = "alice";
    private static final String CONFIGURED_PASSWORD = "operator-configured-admin-password";
    private static final String EARLIER_ROTATED_PASSWORD = "a-password-rotated-earlier";

    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    private final PasswordHasher hasher = new BcryptPasswordHasher(new BCryptPasswordEncoder());
    private final AdminRepository admins = new MemoryAdminRepository();
    private final SessionRepository sessions = new MemorySessionRepository(clock);
    private final JwtSessionManager sessionManager = new JwtSessionManager(new JwtProperties(JWT_TEST_SECRET),
            new SessionProperties(MEMBER_TTL_MINUTES, GUEST_IDLE_MINUTES), sessions, clock);

    @Test
    void GivenDefaultAdminWithTheCommittedPassword_WhenTheContextHasStarted_ThenItsPasswordIsAlreadyTheConfiguredOne() {
        admins.save(defaultAdminWithPassword(CommittedDefaultSecretsGuard.COMMITTED_ADMIN_PASSWORD));

        contextWithTheRotation().run(context -> {
            assertThat(context).hasNotFailed();
            assertTrue(hasher.matches(CONFIGURED_PASSWORD, admins.findById(DEFAULT_ADMIN_ID).getPasswordHash()),
                    "the rotation must be done while the context starts, before runners and the web server");
        });
    }

    @Test
    void GivenAdminTokenIssuedBeforeTheRotation_WhenTheContextHasStarted_ThenTheTokenNoLongerValidatesAsAdmin() {
        admins.save(defaultAdminWithPassword(CommittedDefaultSecretsGuard.COMMITTED_ADMIN_PASSWORD));
        String issuedBefore = sessionManager.generateAdminToken(DEFAULT_ADMIN_ID, DEFAULT_ADMIN_USERNAME);

        contextWithTheRotation().run(context -> {
            assertThat(context).hasNotFailed();
            assertThrows(InvalidTokenException.class, () -> sessionManager.validateAdminToken(issuedBefore),
                    "an admin session issued under the previous password must end when the password is rotated");
        });
    }

    @Test
    void GivenMemberTokenWithTheDefaultAdminsId_WhenTheRotationRuns_ThenTheMembersTokenStillValidates() {
        admins.save(defaultAdminWithPassword(CommittedDefaultSecretsGuard.COMMITTED_ADMIN_PASSWORD));
        String memberToken = sessionManager.generateToken(MEMBER_WITH_THE_ADMINS_ID, MEMBER_USERNAME);
        String adminToken = sessionManager.generateAdminToken(DEFAULT_ADMIN_ID, DEFAULT_ADMIN_USERNAME);

        contextWithTheRotation().run(context -> {
            assertThat(context).hasNotFailed();
            assertThrows(InvalidTokenException.class, () -> sessionManager.validateAdminToken(adminToken),
                    "precondition: the rotation ended the admin's session");
            assertDoesNotThrow(() -> sessionManager.validateToken(memberToken),
                    "a member whose id equals the admin's is not that admin; the member's session must stay");
        });
    }

    @Test
    void GivenAnotherAdminsToken_WhenTheDefaultAdminIsRotated_ThenTheOtherAdminsTokenStillValidates() {
        admins.save(defaultAdminWithPassword(CommittedDefaultSecretsGuard.COMMITTED_ADMIN_PASSWORD));
        admins.save(new Admin(OTHER_ADMIN_ID, OTHER_ADMIN_USERNAME, hasher.hash(EARLIER_ROTATED_PASSWORD), false));
        String otherAdminToken = sessionManager.generateAdminToken(OTHER_ADMIN_ID, OTHER_ADMIN_USERNAME);
        String defaultAdminToken = sessionManager.generateAdminToken(DEFAULT_ADMIN_ID, DEFAULT_ADMIN_USERNAME);

        contextWithTheRotation().run(context -> {
            assertThat(context).hasNotFailed();
            assertThrows(InvalidTokenException.class, () -> sessionManager.validateAdminToken(defaultAdminToken),
                    "precondition: the rotation ended the default admin's session");
            assertTrue(sessionManager.validateAdminToken(otherAdminToken),
                    "only the rotated admin's sessions end");
        });
    }

    @Test
    void GivenDefaultAdminWhosePasswordIsAlreadyAnother_WhenTheContextHasStarted_ThenItsTokenStillValidates() {
        admins.save(defaultAdminWithPassword(EARLIER_ROTATED_PASSWORD));
        String adminToken = sessionManager.generateAdminToken(DEFAULT_ADMIN_ID, DEFAULT_ADMIN_USERNAME);

        contextWithTheRotation().run(context -> {
            assertThat(context).hasNotFailed();
            assertTrue(sessionManager.validateAdminToken(adminToken), "no rotation means no session is ended");
            assertFalse(hasher.matches(CONFIGURED_PASSWORD, admins.findById(DEFAULT_ADMIN_ID).getPasswordHash()),
                    "precondition: the admin's password is the operator's and is not rotated");
        });
    }

    private ApplicationContextRunner contextWithTheRotation() {
        return new ApplicationContextRunner()
                .withBean(AdminRepository.class, () -> admins)
                .withBean(PasswordHasher.class, () -> hasher)
                .withBean(SessionRepository.class, () -> sessions)
                .withBean(SessionManager.class, () -> sessionManager)
                .withBean(PlatformAdminProperties.class,
                        () -> new PlatformAdminProperties(DEFAULT_ADMIN_USERNAME, CONFIGURED_PASSWORD))
                .withBean(CommittedAdminPasswordRotation.class);
    }

    private Admin defaultAdminWithPassword(String rawPassword) {
        return new Admin(DEFAULT_ADMIN_ID, DEFAULT_ADMIN_USERNAME, hasher.hash(rawPassword), true);
    }
}
