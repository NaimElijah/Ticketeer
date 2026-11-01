package com.ticketing.system.unit.infrastructure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.ticketing.system.bootstrap.CommittedAdminPasswordRotation;
import com.ticketing.system.bootstrap.CommittedDefaultSecretsGuard;
import com.ticketing.system.bootstrap.PlatformInitializationRunner;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.governance.config.PlatformAdminProperties;
import com.ticketing.system.identity.adapter.out.persistence.MemoryAdminRepository;
import com.ticketing.system.identity.adapter.out.persistence.MemorySessionRepository;
import com.ticketing.system.identity.adapter.out.security.BcryptPasswordHasher;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.identity.application.port.out.PasswordHasher;
import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.testutil.LogCapture;

/**
 * #34 / #185: {@code CommittedDefaultSecretsGuard} refuses a configured admin password equal to the
 * committed default, but the default admin is created only when no admin exists. A database
 * initialised before the guard therefore still holds a default admin whose password is the committed
 * one, which the public repository tells anyone. At boot, outside {@code dev} and {@code test} and
 * before platform initialisation:
 * <ul>
 *   <li>the default admin whose stored hash matches the committed password gets the configured
 *       password instead, with a WARN that names no secret;</li>
 *   <li>any other admin whose stored hash matches it stops the boot: there is no configured password
 *       to give that admin, and leaving it would keep a known credential (ADR-003).</li>
 * </ul>
 *
 * <p>Runs on the Memory admin repository and the real BCrypt hasher, so "matches the committed
 * password" is a real hash check.
 */
class CommittedAdminPasswordRotationTest {

    private static final int DEFAULT_ADMIN_ID = 1;
    private static final int OTHER_ADMIN_ID = 7;
    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String OTHER_ADMIN_USERNAME = "ops.admin";
    private static final String CONFIGURED_PASSWORD = "operator-configured-admin-password";
    private static final String EARLIER_ROTATED_PASSWORD = "a-password-rotated-earlier";
    private static final String NO_PROFILE = "";

    private final PasswordHasher hasher = new BcryptPasswordHasher(new BCryptPasswordEncoder());
    private final AdminRepository admins = new MemoryAdminRepository();
    private final SessionRepository sessions = new MemorySessionRepository(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    private LogCapture logs;

    @AfterEach
    void detachLogCapture() {
        if (logs != null) {
            logs.close();
        }
    }

    @ParameterizedTest(name = "spring.profiles.active=''{0}''")
    @ValueSource(strings = {NO_PROFILE, "supabase"})
    void GivenDefaultAdminWithTheCommittedPassword_WhenRunOutsideDevAndTest_ThenItsPasswordIsTheConfiguredOne(
            String profile) {
        admins.save(defaultAdminWithPassword(CommittedDefaultSecretsGuard.COMMITTED_ADMIN_PASSWORD));

        rotationUnder(profile).afterSingletonsInstantiated();

        Admin rotated = admins.findById(DEFAULT_ADMIN_ID);
        assertTrue(hasher.matches(CONFIGURED_PASSWORD, rotated.getPasswordHash()),
                "the default admin must now sign in with the configured password");
        assertFalse(hasher.matches(CommittedDefaultSecretsGuard.COMMITTED_ADMIN_PASSWORD, rotated.getPasswordHash()),
                "the committed password must no longer sign the default admin in");
        assertEquals(DEFAULT_ADMIN_USERNAME, rotated.getUsername(), "only the password changes");
        assertTrue(rotated.isDefault(), "only the password changes");
    }

    @Test
    void GivenDefaultAdminWithTheCommittedPassword_WhenRotated_ThenAWarnIsLoggedThatNamesNoSecret() {
        Admin committed = defaultAdminWithPassword(CommittedDefaultSecretsGuard.COMMITTED_ADMIN_PASSWORD);
        admins.save(committed);
        logs = LogCapture.of(CommittedAdminPasswordRotation.class);

        rotationUnder(NO_PROFILE).afterSingletonsInstantiated();

        List<String> logged = logs.eventsContaining("");
        assertTrue(logged.stream().anyMatch(event -> event.startsWith("WARN ")),
                "replacing the default admin's password must be logged at WARN, but the log was " + logged);
        assertEquals(List.of(), logs.eventsContaining(CONFIGURED_PASSWORD), "the configured password must not be logged");
        assertEquals(List.of(), logs.eventsContaining(committed.getPasswordHash()), "the old hash must not be logged");
        assertEquals(List.of(), logs.eventsContaining(admins.findById(DEFAULT_ADMIN_ID).getPasswordHash()),
                "the new hash must not be logged");
    }

    @Test
    void GivenAnotherAdminWithTheCommittedPassword_WhenRun_ThenTheBootStopsAndTheAdminIsUnchanged() {
        admins.save(defaultAdminWithPassword(EARLIER_ROTATED_PASSWORD));
        Admin other = new Admin(OTHER_ADMIN_ID, OTHER_ADMIN_USERNAME,
                hasher.hash(CommittedDefaultSecretsGuard.COMMITTED_ADMIN_PASSWORD), false);
        admins.save(other);
        CommittedAdminPasswordRotation rotation = rotationUnder(NO_PROFILE);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                rotation::afterSingletonsInstantiated,
                "an admin other than the default one with the committed password must stop the boot");

        assertFalse(refused.getMessage().contains(CONFIGURED_PASSWORD), "the refusal must not name the configured password");
        assertFalse(refused.getMessage().contains(other.getPasswordHash()), "the refusal must not name the stored hash");
        assertEquals(other.getPasswordHash(), admins.findById(OTHER_ADMIN_ID).getPasswordHash(),
                "the refused admin is left for the operator to fix");
    }

    @ParameterizedTest(name = "spring.profiles.active=''{0}''")
    @ValueSource(strings = {"dev", "test"})
    void GivenDevOrTestProfile_WhenRun_ThenAnAdminWithTheCommittedPasswordKeepsIt(String profile) {
        Admin committed = defaultAdminWithPassword(CommittedDefaultSecretsGuard.COMMITTED_ADMIN_PASSWORD);
        admins.save(committed);
        admins.save(new Admin(OTHER_ADMIN_ID, OTHER_ADMIN_USERNAME,
                hasher.hash(CommittedDefaultSecretsGuard.COMMITTED_ADMIN_PASSWORD), false));
        CommittedAdminPasswordRotation rotation = rotationUnder(profile);

        assertDoesNotThrow(rotation::afterSingletonsInstantiated,
                profile + " runs on the committed password by design");

        assertEquals(committed.getPasswordHash(), admins.findById(DEFAULT_ADMIN_ID).getPasswordHash(),
                profile + " must leave the default admin's password as it is");
    }

    @Test
    void GivenDefaultAdminWhosePasswordIsAlreadyAnother_WhenRun_ThenItIsUnchanged() {
        Admin rotatedEarlier = defaultAdminWithPassword(EARLIER_ROTATED_PASSWORD);
        admins.save(rotatedEarlier);

        rotationUnder(NO_PROFILE).afterSingletonsInstantiated();

        assertEquals(rotatedEarlier.getPasswordHash(), admins.findById(DEFAULT_ADMIN_ID).getPasswordHash(),
                "a password that is not the committed one is the operator's, and stays");
    }

    @Test
    void GivenNoAdminYet_WhenRun_ThenNothingHappens() {
        CommittedAdminPasswordRotation rotation = rotationUnder(NO_PROFILE);

        assertDoesNotThrow(rotation::afterSingletonsInstantiated,
                "a first boot has no admin yet; platform initialisation creates it with the configured password");
        assertFalse(admins.existsAny());
    }

    // The rotation must have happened before platform initialisation looks at the admins. Spring Boot
    // calls the runners once the context has started, as this test does with PlatformInitializationRunner.
    @Test
    void GivenDefaultAdminWithTheCommittedPassword_WhenPlatformInitialisationRuns_ThenTheAdminAlreadyHasTheConfiguredPassword() {
        admins.save(defaultAdminWithPassword(CommittedDefaultSecretsGuard.COMMITTED_ADMIN_PASSWORD));
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        AtomicBoolean rotatedWhenInitialised = new AtomicBoolean();
        doAnswer(invocation -> {
            rotatedWhenInitialised.set(
                    hasher.matches(CONFIGURED_PASSWORD, admins.findById(DEFAULT_ADMIN_ID).getPasswordHash()));
            return null;
        }).when(systemAdminService).initializePlatform();

        new ApplicationContextRunner()
                .withBean(AdminRepository.class, () -> admins)
                .withBean(PasswordHasher.class, () -> hasher)
                .withBean(SessionRepository.class, () -> sessions)
                .withBean(PlatformAdminProperties.class,
                        () -> new PlatformAdminProperties(DEFAULT_ADMIN_USERNAME, CONFIGURED_PASSWORD))
                .withBean(SystemAdminService.class, () -> systemAdminService)
                .withBean(CommittedAdminPasswordRotation.class)
                .withBean(PlatformInitializationRunner.class)
                .run(context -> context.getBean(PlatformInitializationRunner.class)
                        .run(new DefaultApplicationArguments()));

        assertTrue(rotatedWhenInitialised.get(),
                "platform initialisation must find the default admin already on the configured password");
    }

    private Admin defaultAdminWithPassword(String rawPassword) {
        return new Admin(DEFAULT_ADMIN_ID, DEFAULT_ADMIN_USERNAME, hasher.hash(rawPassword), true);
    }

    private CommittedAdminPasswordRotation rotationUnder(String profile) {
        MockEnvironment environment = new MockEnvironment();
        if (!profile.isEmpty()) {
            environment.setActiveProfiles(profile);
        }
        return new CommittedAdminPasswordRotation(environment, admins, hasher, sessions,
                new PlatformAdminProperties(DEFAULT_ADMIN_USERNAME, CONFIGURED_PASSWORD));
    }
}
