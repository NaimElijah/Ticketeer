package com.ticketing.system.unit.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.bind.validation.ValidationErrors;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.validation.FieldError;

import com.ticketing.system.bootstrap.config.MarketProperties;
import com.ticketing.system.bootstrap.config.SeedProperties;
import com.ticketing.system.governance.config.PlatformAdminProperties;
import com.ticketing.system.identity.config.LockoutProperties;
import com.ticketing.system.identity.config.SessionProperties;
import com.ticketing.system.sales.config.ReservationHoldProperties;
import com.ticketing.system.shared.config.PlatformTimeProperties;
import com.ticketing.system.shared.config.WsepProperties;

/**
 * V3 4.b / ADR-003: an out-of-bounds initialization parameter stops the context from starting, with
 * a bind failure that names the offending field. The baseline is the shipped {@code application.yml}.
 */
class InitPropertiesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(InitProperties.class);

    @EnableConfigurationProperties({
            ReservationHoldProperties.class, SessionProperties.class, LockoutProperties.class,
            WsepProperties.class, PlatformAdminProperties.class, MarketProperties.class, SeedProperties.class,
            PlatformTimeProperties.class})
    static class InitProperties {
    }

    @Test
    void GivenShippedDefaults_WhenContextStarts_ThenEveryRecordBinds() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ReservationHoldProperties.class).ticketReservationDuration()).isEqualTo(10);
            assertThat(context.getBean(SessionProperties.class).memberTtlMinutes()).isEqualTo(1440);
            assertThat(context.getBean(LockoutProperties.class).maxAttempts()).isEqualTo(5);
            assertThat(context.getBean(WsepProperties.class).requestTimeoutSeconds()).isEqualTo(20);
            assertThat(context.getBean(PlatformAdminProperties.class).username()).isEqualTo("admin");
            assertThat(context.getBean(MarketProperties.class).selfHealDelayMs()).isEqualTo(30000);
            assertThat(context.getBean(SeedProperties.class).mode()).isEqualTo(SeedProperties.Mode.OFF);
            assertThat(context.getBean(PlatformTimeProperties.class).timeZone()).isEqualTo(ZoneId.of("Asia/Jerusalem"));
        });
    }

    @ParameterizedTest(name = "platform.time-zone=''{0}''")
    @ValueSource(strings = {"Mars/Olympus", ""})
    void GivenInvalidTimeZone_WhenContextStarts_ThenFailsNamingTheProperty(String zone) {
        runner.withPropertyValues("platform.time-zone=" + zone).run(context -> {
            assertThat(context).hasFailed();
            BindException bindFailure = causeOfType(context.getStartupFailure(), BindException.class);
            assertThat(bindFailure.getProperty().getName()).hasToString("platform.time-zone");
        });
    }

    @Test
    void GivenZeroLockoutAttempts_WhenContextStarts_ThenBindsBecauseZeroDisablesTheLockout() {
        runner.withPropertyValues("auth.lockout.max-attempts=0").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LockoutProperties.class).maxAttempts()).isZero();
        });
    }

    @Test
    void GivenUnknownSeedMode_WhenContextStarts_ThenFailsNamingTheProperty() {
        runner.withPropertyValues("seed.mode=resed").run(context -> {
            assertThat(context).hasFailed();
            BindException bindFailure = causeOfType(context.getStartupFailure(), BindException.class);
            assertThat(bindFailure.getProperty().getName()).hasToString("seed.mode");
        });
    }

    @Test
    void GivenSeedModeInLowerCase_WhenContextStarts_ThenBinds() {
        runner.withPropertyValues("seed.mode=reseed").run(context ->
                assertThat(context.getBean(SeedProperties.class).mode()).isEqualTo(SeedProperties.Mode.RESEED));
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "constants.ticket-reservation-duration, 0,         ticketReservationDuration",
            "session.member-ttl-minutes,            -1,        memberTtlMinutes",
            "session.guest-idle-timeout-minutes,    0,         guestIdleTimeoutMinutes",
            "auth.lockout.max-attempts,             -1,        maxAttempts",
            "auth.lockout.lock-minutes,             0,         lockMinutes",
            "wsep.base-url,                         '',        baseUrl",
            "wsep.base-url,                         not a url, baseUrl",
            "wsep.request-timeout-seconds,          0,         requestTimeoutSeconds",
            "wsep.handshake-attempts,               0,         handshakeAttempts",
            "wsep.handshake-backoff-ms,             -1,        handshakeBackoffMs",
            "platform.admin.username,               '',        username",
            "platform.admin.password,               '',        password",
            "market.self-heal-delay-ms,             0,         selfHealDelayMs",
            "seed.scenario,                         '',        scenario",
    })
    void GivenOutOfBoundsValue_WhenContextStarts_ThenFailsNamingTheField(String property, String value, String field) {
        runner.withPropertyValues(property + "=" + value).run(context -> {
            assertThat(context).hasFailed();
            Throwable root = NestedExceptionUtils.getRootCause(context.getStartupFailure());
            assertThat(root).isInstanceOf(BindValidationException.class);
            ValidationErrors errors = ((BindValidationException) root).getValidationErrors();
            assertThat(errors.getName()).hasToString(property.substring(0, property.lastIndexOf('.')));
            assertThat(errors.getAllErrors())
                    .extracting(error -> ((FieldError) error).getField())
                    .containsExactly(field);
        });
    }

    private static <T extends Throwable> T causeOfType(Throwable failure, Class<T> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " in the cause chain of " + failure);
    }
}
