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
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;

import com.ticketing.system.bootstrap.CommittedDefaultSecretsGuard;
import com.ticketing.system.governance.config.PlatformAdminProperties;

/**
 * #34 / #185: a boot outside {@code dev} and {@code test} must not run with the secrets committed in
 * {@code application.yml}. The baseline is the shipped {@code application.yml}, loaded the way Boot loads
 * it, so each case starts from the committed defaults and overrides only what it names.
 */
class CommittedDefaultSecretsGuardTest {

    private static final String JWT_SECRET_KEY = "jwt.secret";
    private static final String ADMIN_PASSWORD_KEY = "platform.admin.password";
    private static final String OPERATOR_JWT_SECRET =
            "operator-supplied-secret-that-is-comfortably-longer-than-256-bits-0123456789";
    private static final String OPERATOR_ADMIN_PASSWORD = "operator-supplied-admin-password";
    private static final String SHIPPED_CONFIG = "src/main/resources/application.yml";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(GuardOnly.class);

    @EnableConfigurationProperties(PlatformAdminProperties.class)
    @Import(CommittedDefaultSecretsGuard.class)
    static class GuardOnly {
    }

    @ParameterizedTest(name = "spring.profiles.active=''{0}''")
    @ValueSource(strings = {"", "supabase"})
    void GivenNoProfileAndTheCommittedJwtSecret_WhenStarting_ThenTheBootFails(String profiles) {
        runner.withPropertyValues(
                        "spring.profiles.active=" + profiles,
                        ADMIN_PASSWORD_KEY + "=" + OPERATOR_ADMIN_PASSWORD)
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
        runner.withPropertyValues(
                        "spring.profiles.active=" + profiles,
                        JWT_SECRET_KEY + "=" + OPERATOR_JWT_SECRET)
                .run(context -> {
                    assertThat(context)
                            .as("a boot with the committed platform.admin.password outside dev/test must fail")
                            .hasFailed();
                    assertThat(messagesOf(context.getStartupFailure()))
                            .as("the failure names the offending key")
                            .anyMatch(message -> message.contains(ADMIN_PASSWORD_KEY));
                });
    }

    @Test
    void GivenDevProfileAndTheCommittedDefaults_WhenStarting_ThenTheBootProceeds() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).as("dev runs on the committed defaults by design").hasNotFailed();
            assertThat(context.getEnvironment().getProperty(JWT_SECRET_KEY))
                    .isEqualTo(CommittedDefaultSecretsGuard.COMMITTED_JWT_SECRET);
            assertThat(context.getBean(PlatformAdminProperties.class).password())
                    .isEqualTo(CommittedDefaultSecretsGuard.COMMITTED_ADMIN_PASSWORD);
        });
    }

    // application-test.yml carries its own jwt.secret, so the committed one is set explicitly: the
    // case under test is the test profile with both committed defaults, not the test profile's file.
    @Test
    void GivenTestProfileAndTheCommittedDefaults_WhenStarting_ThenTheBootProceeds() {
        runner.withPropertyValues(
                        "spring.profiles.active=test",
                        JWT_SECRET_KEY + "=" + CommittedDefaultSecretsGuard.COMMITTED_JWT_SECRET)
                .run(context -> {
                    assertThat(context).as("the test suite may run on the committed defaults").hasNotFailed();
                    assertThat(context.getBean(PlatformAdminProperties.class).password())
                            .isEqualTo(CommittedDefaultSecretsGuard.COMMITTED_ADMIN_PASSWORD);
                });
    }

    @Test
    void GivenOverriddenSecrets_WhenStarting_ThenTheBootProceeds() {
        runner.withPropertyValues(
                        JWT_SECRET_KEY + "=" + OPERATOR_JWT_SECRET,
                        ADMIN_PASSWORD_KEY + "=" + OPERATOR_ADMIN_PASSWORD)
                .run(context -> assertThat(context)
                        .as("a boot with both secrets supplied by the operator must start")
                        .hasNotFailed());
    }

    @Test
    void GivenTheShippedApplicationYml_WhenReadingEachSecretDefault_ThenItEqualsTheGuardConstant() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(SHIPPED_CONFIG));
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
