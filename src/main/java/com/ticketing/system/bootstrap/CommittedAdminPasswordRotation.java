package com.ticketing.system.bootstrap;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import com.ticketing.system.governance.config.PlatformAdminProperties;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.identity.application.port.out.PasswordHasher;
import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.domain.Admin;

import lombok.extern.slf4j.Slf4j;

/**
 * Takes the committed default password off every stored System Admin at boot, outside the {@code dev}
 * and {@code test} profiles (ADR-014).
 *
 * <p>{@link CommittedDefaultSecretsGuard} refuses a configured {@code platform.admin.password} equal to
 * the committed default, but the default admin is created only when no admin exists. A database first
 * started with the default therefore keeps an admin whose password is the one the public repository
 * shows, whatever is configured later, and no admin password can be changed from the platform yet
 * (#77). So:
 * <ul>
 *   <li>the default admin whose stored hash matches
 *       {@link CommittedDefaultSecretsGuard#COMMITTED_ADMIN_PASSWORD} gets the configured password,
 *       logged at WARN without either value. Its existing admin sessions end with the old password:
 *       a token issued under it no longer validates (ADR-012);</li>
 *   <li>any other admin whose stored hash matches it stops the boot (ADR-003): there is no configured
 *       password to give that admin. Each such admin's admin sessions end first, so once the operator
 *       replaces the hash and starts again, no session issued under the committed password remains.
 *       Nothing else is changed, the default admin included.</li>
 * </ul>
 *
 * <p>It runs while the application context starts, once every singleton exists
 * ({@link SmartInitializingSingleton}): before any {@code ApplicationRunner}, so before
 * {@link PlatformInitializationRunner} reads the admins, and before the web server takes requests.
 *
 * <p>The profile is read from the {@link Environment} at run time rather than through {@code @Profile},
 * as {@link CommittedDefaultSecretsGuard} does, so the rule and its exemption live in one class.
 */
@Component
@Slf4j
public class CommittedAdminPasswordRotation implements SmartInitializingSingleton {

    private static final Profiles PROFILES_ALLOWED_COMMITTED_SECRETS = Profiles.of("dev | test");

    private final Environment environment;
    private final AdminRepository admins;
    private final PasswordHasher passwordHasher;
    private final SessionRepository sessions;
    private final PlatformAdminProperties adminProperties;

    /**
     * @param environment     read for the active profiles
     * @param admins          the stored System Admins
     * @param passwordHasher  checks stored hashes against the committed password and hashes the
     *                        configured one
     * @param sessions        the session store, whose rows of the rotated admin are deleted
     * @param adminProperties the configured default-admin credentials, read for the password
     */
    public CommittedAdminPasswordRotation(Environment environment, AdminRepository admins,
            PasswordHasher passwordHasher, SessionRepository sessions, PlatformAdminProperties adminProperties) {
        this.environment = environment;
        this.admins = admins;
        this.passwordHasher = passwordHasher;
        this.sessions = sessions;
        this.adminProperties = adminProperties;
    }

    /**
     * Rotates the default admin off the committed password and ends its admin sessions, or refuses to
     * start. Called by Spring while the context starts; a throw fails the context refresh.
     *
     * @throws IllegalStateException outside {@code dev} and {@code test}, when an admin other than the
     *         default one still has the committed password, after ending that admin's admin sessions; the
     *         message names the admin's id, never a password or a hash
     */
    @Override
    public void afterSingletonsInstantiated() {
        if (environment.acceptsProfiles(PROFILES_ALLOWED_COMMITTED_SECRETS)) {
            return;
        }
        List<Admin> onCommittedPassword = admins.findAll().stream()
                .filter(admin -> passwordHasher.matches(
                        CommittedDefaultSecretsGuard.COMMITTED_ADMIN_PASSWORD, admin.getPasswordHash()))
                .toList();
        List<Integer> otherAdminIds = onCommittedPassword.stream()
                .filter(admin -> !admin.isDefault())
                .map(Admin::getId)
                .toList();
        if (!otherAdminIds.isEmpty()) {
            // No admin password can be changed from the platform yet (#77), so the operator replaces the
            // stored hash in the admins table. Their sessions end now, so none issued under the committed
            // password outlives that fix.
            otherAdminIds.forEach(sessions::deleteAdminSessions);
            throw new IllegalStateException("Refusing to start outside the dev and test profiles: the System "
                    + "Admin(s) with id " + otherAdminIds + " still have the default admin password committed "
                    + "in application.yml, and their admin sessions have been ended. Replace each one's stored "
                    + "password hash, then start again.");
        }
        Optional<Admin> defaultAdmin = onCommittedPassword.stream().filter(Admin::isDefault).findFirst();
        defaultAdmin.ifPresent(this::giveTheConfiguredPassword);
    }

    private void giveTheConfiguredPassword(Admin defaultAdmin) {
        String configuredHash = passwordHasher.hash(adminProperties.password());
        // Sessions end before the new hash is set and stored: if ending them fails, the admin still has the
        // committed password, so the next boot does both steps again. Stored first, a failure here would
        // leave the old sessions with nothing left to end them.
        sessions.deleteAdminSessions(defaultAdmin.getId());
        defaultAdmin.changePasswordHash(configuredHash);
        admins.save(defaultAdmin);
        log.warn("The default System Admin id={} still had the default password committed in "
                + "application.yml; it now has the configured platform.admin.password (PLATFORM_ADMIN_PASSWORD), "
                + "and its existing admin sessions have ended.",
                defaultAdmin.getId());
    }
}
