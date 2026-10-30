package com.ticketing.system.bootstrap;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import com.ticketing.system.governance.config.PlatformAdminProperties;

/**
 * Stops the boot when a run outside the {@code dev} and {@code test} profiles still uses a secret
 * whose default is committed in {@code application.yml} (#34, ADR-014).
 *
 * <p>The repository is public, so the committed JWT signing secret and default admin password are
 * known to anyone: a deployment that keeps either lets a stranger forge sessions or sign in as the
 * System Admin. Under ADR-003 a misconfiguration is fatal, so the constructor throws and the context
 * never starts, instead of the platform coming up with a known credential. {@code dev} and
 * {@code test} run on the committed values by design.
 *
 * <p>The guard checks the configured password only. The password already stored for an admin is
 * {@link CommittedAdminPasswordRotation}'s to check, at boot (ADR-014).
 *
 * <p>The two constants repeat the defaults in {@code application.yml} because the guard has to know
 * which value is the committed one, and a resolved property cannot say whether it came from the
 * default or from the environment. {@code CommittedDefaultSecretsGuardTest} reads the shipped
 * {@code application.yml} and fails if either default drifts from its constant.
 */
@Component
public class CommittedDefaultSecretsGuard {

    /** The {@code jwt.secret} default committed in {@code application.yml}. */
    public static final String COMMITTED_JWT_SECRET =
            "dev-only-secret-please-override-in-prod-must-be-at-least-256-bits-long";

    /** The {@code platform.admin.password} default committed in {@code application.yml}. */
    public static final String COMMITTED_ADMIN_PASSWORD = "admin";

    private static final Profiles PROFILES_ALLOWED_COMMITTED_SECRETS = Profiles.of("dev | test");
    private static final String JWT_SECRET_KEY = "jwt.secret";
    private static final String JWT_SECRET_ENV = "JWT_SECRET";
    private static final String ADMIN_PASSWORD_KEY = "platform.admin.password";
    private static final String ADMIN_PASSWORD_ENV = "PLATFORM_ADMIN_PASSWORD";

    /**
     * Checks the secrets once, at context start.
     *
     * @param environment     the application's environment, read for {@code jwt.secret} and the active
     *                        profiles
     * @param adminProperties the bound default-admin credentials, read for the password
     * @throws IllegalStateException when neither {@code dev} nor {@code test} is active and either
     *         secret equals its committed default; the message names each such key and the
     *         environment variable that overrides it, never the value
     */
    public CommittedDefaultSecretsGuard(Environment environment, PlatformAdminProperties adminProperties) {
        if (environment.acceptsProfiles(PROFILES_ALLOWED_COMMITTED_SECRETS)) {
            return;
        }
        List<String> committedKeys = new ArrayList<>();
        if (COMMITTED_JWT_SECRET.equals(environment.getProperty(JWT_SECRET_KEY))) {
            committedKeys.add(JWT_SECRET_KEY + " (set " + JWT_SECRET_ENV + ")");
        }
        if (COMMITTED_ADMIN_PASSWORD.equals(adminProperties.password())) {
            committedKeys.add(ADMIN_PASSWORD_KEY + " (set " + ADMIN_PASSWORD_ENV + ")");
        }
        if (!committedKeys.isEmpty()) {
            throw new IllegalStateException("Refusing to start outside the dev and test profiles with the "
                    + "default committed in application.yml for: " + String.join(", ", committedKeys));
        }
    }
}
