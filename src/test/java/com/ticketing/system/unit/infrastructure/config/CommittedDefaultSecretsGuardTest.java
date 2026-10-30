package com.ticketing.system.unit.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;

import com.ticketing.system.bootstrap.CommittedDefaultSecretsGuard;
import com.ticketing.system.governance.config.PlatformAdminProperties;

/**
 * #34 / #185: a boot outside {@code dev} and {@code test} must not run with the secrets committed in
 * {@code application.yml}. Each case sets both secrets explicitly, to the committed constants or to
 * operator values, so a {@code JWT_SECRET} or {@code PLATFORM_ADMIN_PASSWORD} exported in the
 * developer's shell cannot change its outcome. The drift test is the one reader of the shipped
 * {@code application.yml}: it pins the constants to the defaults committed there.
 */
class CommittedDefaultSecretsGuardTest {

    private static final String PROFILES_KEY = "spring.profiles.active";
    private static final String JWT_SECRET_KEY = "jwt.secret";
    private static final String ADMIN_USERNAME_KEY = "platform.admin.username";
    private static final String ADMIN_PASSWORD_KEY = "platform.admin.password";
    private static final String ADMIN_USERNAME = "admin";
    private static final String OPERATOR_JWT_SECRET =
            "operator-supplied-secret-that-is-comfortably-longer-than-256-bits-0123456789";
    private static final String OPERATOR_ADMIN_PASSWORD = "operator-supplied-admin-password";
    private static final String SHIPPED_CONFIG = "application.yml";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GuardOnly.class);

    @EnableConfigurationProperties(PlatformAdminProperties.class)
    @Import(CommittedDefaultSecretsGuard.class)
    static class GuardOnly {
    }

    /** The runner with the given profiles and both secrets set explicitly. */
    private ApplicationContextRunner startingWith(String profiles, String jwtSecret, String adminPassword) {
        return runner.withPropertyValues(
                PROFILES_KEY + "=" + profiles,
                JWT_SECRET_KEY + "=" + jwtSecret,
                ADMIN_USERNAME_KEY + "=" + ADMIN_USERNAME,
                ADMIN_PASSWORD_KEY + "=" + adminPassword);
    }

    @ParameterizedTest(name = "spring.profiles.active=''{0}''")
    @ValueSource(strings = {"", "supabase"})
    void GivenNoProfileAndTheCommittedJwtSecret_WhenStarting_ThenTheBootFails(String profiles) {
        startingWith(profiles, CommittedDefaultSecretsGuard.COMMITTED_JWT_SECRET, OPERATOR_ADMIN_PASSWORD)
                .run(context -> {
                    assertThat(context)
                            .as("a boot with the committed jwt.secret outside dev/test must fail")
                            .hasFailed();
                    assertThat(messagesOf(context.getStartupFailure()))
                            .as("the failure names the offending key")
                            .anyMatch(message -> message.contains(JWT_SECRET_KEY));
                    assertThat(messagesOf(context.getStartupFailure()))
                            .as("the failure never echoes the secret itself")
                            .noneMatch(message -> message.contains(CommittedDefaultSecretsGuard.COMMITTED_JWT_SECRET));
                });
    }

    @ParameterizedTest(name = "spring.profiles.active=''{0}''")
    @ValueSource(strings = {"", "supabase"})
    void GivenNoProfileAndTheCommittedAdminPassword_WhenStarting_ThenTheBootFails(String profiles) {
        startingWith(profiles, OPERATOR_JWT_SECRET, CommittedDefaultSecretsGuard.COMMITTED_ADMIN_PASSWORD)
                .run(context -> {
                    assertThat(context)
                            .as("a boot with the committed platform.admin.password outside dev/test must fail")
                            .hasFailed();
                    assertThat(messagesOf(context.getStartupFailure()))
                            .as("the failure names the offending key")
                            .anyMatch(message -> message.contains(ADMIN_PASSWORD_KEY));
                });
    }

    @ParameterizedTest(name = "spring.profiles.active=''{0}''")
    @ValueSource(strings = {"dev", "test"})
    void GivenDevOrTestProfileAndTheCommittedDefaults_WhenStarting_ThenTheBootProceeds(String profiles) {
        startingWith(profiles, CommittedDefaultSecretsGuard.COMMITTED_JWT_SECRET,
                        CommittedDefaultSecretsGuard.COMMITTED_ADMIN_PASSWORD)
                .run(context -> assertThat(context)
                        .as("%s runs on the committed defaults by design", profiles)
                        .hasNotFailed());
    }

    @Test
    void GivenOverriddenSecrets_WhenStarting_ThenTheBootProceeds() {
        startingWith("", OPERATOR_JWT_SECRET, OPERATOR_ADMIN_PASSWORD)
                .run(context -> assertThat(context)
                        .as("a boot with both secrets supplied by the operator must start")
                        .hasNotFailed());
    }

    @Test
    void GivenTheShippedApplicationYml_WhenReadingEachSecretDefault_ThenItEqualsTheGuardConstant() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(SHIPPED_CONFIG));
        Properties shipped = yaml.getObject();

        assertThat(placeholderDefault(shipped, JWT_SECRET_KEY))
                .as("the guard's committed jwt.secret must match application.yml")
                .isEqualTo(CommittedDefaultSecretsGuard.COMMITTED_JWT_SECRET);
        assertThat(placeholderDefault(shipped, ADMIN_PASSWORD_KEY))
                .as("the guard's committed platform.admin.password must match application.yml")
                .isEqualTo(CommittedDefaultSecretsGuard.COMMITTED_ADMIN_PASSWORD);
    }

    /** The default of a {@code ${ENV:default}} value, failing the test if the value has another shape. */
    private static String placeholderDefault(Properties shipped, String key) {
        String raw = shipped.getProperty(key);
        assertThat(raw).as(key + " in " + SHIPPED_CONFIG).isNotNull().startsWith("${").endsWith("}");
        String inner = raw.substring(2, raw.length() - 1);
        int separator = inner.indexOf(':');
        assertThat(separator).as(key + " must be ${ENV:default}, was " + raw).isPositive();
        return inner.substring(separator + 1);
    }

    private static List<String> messagesOf(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null) {
                messages.add(cause.getMessage());
            }
        }
        return messages;
    }
}
