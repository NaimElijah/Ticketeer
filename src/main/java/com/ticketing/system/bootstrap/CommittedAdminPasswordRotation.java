package com.ticketing.system.bootstrap;

import java.util.List;
import java.util.Optional;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import com.ticketing.system.governance.config.PlatformAdminProperties;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.identity.application.port.out.PasswordHasher;
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
 * (#77). So, before {@link PlatformInitializationRunner} runs:
 * <ul>
 *   <li>the default admin whose stored hash matches
 *       {@link CommittedDefaultSecretsGuard#COMMITTED_ADMIN_PASSWORD} gets the configured password,
 *       logged at WARN without either value;</li>
 *   <li>any other admin whose stored hash matches it stops the boot (ADR-003): there is no configured
 *       password to give that admin. Nothing is changed then, the default admin included.</li>
 * </ul>
 *
 * <p>The profile is read from the {@link Environment} at run time rather than through {@code @Profile},
 * as {@link CommittedDefaultSecretsGuard} does, so the rule and its exemption live in one class.
 */
@Component
// Before PlatformInitializationRunner's @Order(0): platform initialisation reads the admins.
@Order(-1)
@Slf4j
public class CommittedAdminPasswordRotation implements ApplicationRunner {

    private static final Profiles PROFILES_ALLOWED_COMMITTED_SECRETS = Profiles.of("dev | test");

    private final Environment environment;
    private final AdminRepository admins;
    private final PasswordHasher passwordHasher;
    private final PlatformAdminProperties adminProperties;

    /**
     * @param environment     read for the active profiles
     * @param admins          the stored System Admins
     * @param passwordHasher  checks stored hashes against the committed password and hashes the
     *                        configured one
     * @param adminProperties the configured default-admin credentials, read for the password
     */
    public CommittedAdminPasswordRotation(Environment environment, AdminRepository admins,
            PasswordHasher passwordHasher, PlatformAdminProperties adminProperties) {
        this.environment = environment;
        this.admins = admins;
        this.passwordHasher = passwordHasher;
        this.adminProperties = adminProperties;
    }

    /**
     * Rotates the default admin off the committed password, or refuses to start.
     *
     * @throws IllegalStateException outside {@code dev} and {@code test}, when an admin other than the
     *         default one still has the committed password; the message names the admin's id, never a
     *         password or a hash
     */
    @Override
    public void run(ApplicationArguments args) {
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
            // stored hash in the admins table.
            throw new IllegalStateException("Refusing to start outside the dev and test profiles: the System "
                    + "Admin(s) with id " + otherAdminIds + " still have the default admin password committed "
                    + "in application.yml. Replace each one's stored password hash, then start again.");
        }
        Optional<Admin> defaultAdmin = onCommittedPassword.stream().filter(Admin::isDefault).findFirst();
        defaultAdmin.ifPresent(this::giveTheConfiguredPassword);
    }

    private void giveTheConfiguredPassword(Admin defaultAdmin) {
        defaultAdmin.changePasswordHash(passwordHasher.hash(adminProperties.password()));
        admins.save(defaultAdmin);
        log.warn("The default System Admin id={} still had the default password committed in "
                + "application.yml; it now has the configured platform.admin.password (PLATFORM_ADMIN_PASSWORD).",
                defaultAdmin.getId());
    }
}
