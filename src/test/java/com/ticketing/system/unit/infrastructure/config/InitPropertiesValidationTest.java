package com.ticketing.system.unit.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.ConfigurationPropertiesBean;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.bind.validation.ValidationErrors;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.validation.FieldError;

import com.ticketing.system.bootstrap.config.EventCompletionProperties;
import com.ticketing.system.bootstrap.config.MarketProperties;
import com.ticketing.system.bootstrap.config.NotificationPollingProperties;
import com.ticketing.system.bootstrap.config.SeedProperties;
import com.ticketing.system.bootstrap.config.SweeperProperties;
import com.ticketing.system.catalog.config.VenueLimitsProperties;
import com.ticketing.system.governance.config.AnalyticsProperties;
import com.ticketing.system.governance.config.PlatformAdminProperties;
import com.ticketing.system.identity.adapter.out.persistence.MemorySessionRepository;
import com.ticketing.system.identity.adapter.out.security.JwtSessionManager;
import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.config.IdentityProperties;
import com.ticketing.system.identity.config.JwtProperties;
import com.ticketing.system.identity.config.LockoutProperties;
import com.ticketing.system.identity.config.SessionProperties;
import com.ticketing.system.sales.config.ReservationHoldProperties;
import com.ticketing.system.shared.config.DatabaseProperties;
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
            PlatformTimeProperties.class, AnalyticsProperties.class, SweeperProperties.class,
            EventCompletionProperties.class, NotificationPollingProperties.class, IdentityProperties.class,
            VenueLimitsProperties.class, DatabaseProperties.class})
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
            assertThat(context.getBean(AnalyticsProperties.class).rateWindowMinutes()).isEqualTo(5);
            assertThat(context.getBean(AnalyticsProperties.class).refreshIntervalMs()).isEqualTo(5000);
            assertThat(context.getBean(SweeperProperties.class).fixedDelayMs()).isEqualTo(60000);
            assertThat(context.getBean(EventCompletionProperties.class).fixedDelayMs()).isEqualTo(60000);
            assertThat(context.getBean(NotificationPollingProperties.class).pollDelayMs()).isEqualTo(60000);
            assertThat(context.getBean(IdentityProperties.class).reservedUsernames())
                    .containsExactly("admin", "platform.admin");
            assertThat(context.getBean(IdentityProperties.class).platformAdminUsername()).isEqualTo("admin");
            assertThat(context.getBean(DatabaseProperties.class).socketTimeout()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    // #76: the default admin's username is always reserved, so identity follows platform.admin.username
    // without importing governance's PlatformAdminProperties.
    @Test
    void GivenPlatformAdminUsernameOverride_WhenContextStarts_ThenIdentityBindsTheSameName() {
        runner.withPropertyValues("platform.admin.username=root.admin").run(context ->
                assertThat(context.getBean(IdentityProperties.class).platformAdminUsername()).isEqualTo("root.admin"));
    }

    @Test
    void GivenReservedUsernamesList_WhenContextStarts_ThenBindsEveryName() {
        runner.withPropertyValues("identity.reserved-usernames=box.office,support").run(context ->
                assertThat(context.getBean(IdentityProperties.class).reservedUsernames())
                        .isEqualTo(List.of("box.office", "support")));
    }

    @Test
    void GivenEmptyReservedUsernames_WhenContextStarts_ThenBindsBecauseOnlyTheAdminNameIsThenReserved() {
        runner.withPropertyValues("identity.reserved-usernames=").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IdentityProperties.class).reservedUsernames()).allMatch(String::isBlank);
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
    void GivenZeroAnalyticsRefreshInterval_WhenContextStarts_ThenBindsBecauseZeroDisablesTheAutoRefresh() {
        runner.withPropertyValues("analytics.refresh-interval-ms=0").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AnalyticsProperties.class).refreshIntervalMs()).isZero();
        });
    }

    // #187: InMemorySystemMetrics keeps two hours of events, so a longer rate window would be computed
    // over a truncated count.
    @Test
    void GivenRateWindowLongerThanMetricsRetention_WhenContextStarts_ThenFailsNamingTheField() {
        runner.withPropertyValues("analytics.rate-window-minutes=121").run(context -> {
            assertThat(context).as("a rate window over the 120-minute metrics retention must stop the boot")
                    .hasFailed();
            Throwable root = NestedExceptionUtils.getRootCause(context.getStartupFailure());
            assertThat(root).isInstanceOf(BindValidationException.class);
            ValidationErrors errors = ((BindValidationException) root).getValidationErrors();
            assertThat(errors.getName()).hasToString("analytics");
            assertThat(errors.getAllErrors())
                    .extracting(error -> ((FieldError) error).getField())
                    .containsExactly("rateWindowMinutes");
        });
    }

    @Test
    void GivenRateWindowEqualToMetricsRetention_WhenContextStarts_ThenBinds() {
        runner.withPropertyValues("analytics.rate-window-minutes=120").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AnalyticsProperties.class).rateWindowMinutes()).isEqualTo(120);
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
            "analytics.rate-window-minutes,         0,         rateWindowMinutes",
            "analytics.refresh-interval-ms,         -1,        refreshIntervalMs",
            "sweeper.fixed-delay-ms,                0,         fixedDelayMs",
            "event-completion.fixed-delay-ms,       0,         fixedDelayMs",
            "notifications.poll-delay-ms,           0,         pollDelayMs",
            "database.socket-timeout,               0s,        socketTimeout",
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

    // #185: the base URL carries card data on every pay call, so only TLS is acceptable.
    @ParameterizedTest(name = "wsep.base-url={0}")
    @ValueSource(strings = {"http://wsep.example.com/", "ftp://wsep.example.com/"})
    void GivenNonHttpsWsepBaseUrl_WhenContextStarts_ThenFailsNamingBaseUrl(String baseUrl) {
        runner.withPropertyValues("wsep.base-url=" + baseUrl).run(context -> {
            assertThat(context).as("a non-https WSEP base URL must stop the boot").hasFailed();
            Throwable root = NestedExceptionUtils.getRootCause(context.getStartupFailure());
            assertThat(root).isInstanceOf(BindValidationException.class);
            ValidationErrors errors = ((BindValidationException) root).getValidationErrors();
            assertThat(errors.getAllErrors())
                    .extracting(error -> ((FieldError) error).getField())
                    .containsExactly("baseUrl");
        });
    }

    @Test
    void GivenHttpsWsepBaseUrl_WhenContextStarts_ThenBinds() {
        runner.withPropertyValues("wsep.base-url=https://wsep.example.com/").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(WsepProperties.class).baseUrl()).isEqualTo("https://wsep.example.com/");
        });
    }

    // ---- jwt.secret (#187) ----
    // The HMAC secret binds to a @Validated record in identity's config package, like every other
    // init parameter, so a blank secret stops the context by its property name. A secret shorter than
    // the 256 bits (32 bytes) jjwt's HS256 needs stops it too, naming jwt.secret, but never echoing
    // the value: a bind-validation failure report prints the rejected value, so a short secret must
    // not be refused by a binding constraint. The runner scans that package, so it binds whichever
    // record carries the key.

    private static final int MIN_JWT_SECRET_BYTES = 32;
    // One character short of the minimum, and distinctive, so a failure that echoes it is found.
    private static final String SHORT_JWT_SECRET = "Zq7-short-jwt-secret-31-chars!!";
    private static final String JWT_SECRET = "a-jwt-secret-long-enough-for-hmac-sha-256-signing";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final ApplicationContextRunner identityConfigRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(IdentityConfigScan.class);

    @ConfigurationPropertiesScan(basePackageClasses = IdentityProperties.class)
    static class IdentityConfigScan {
    }

    // The identity config records plus the component that signs with the secret, so a secret refused
    // anywhere between binding and the first token stops this context.
    private final ApplicationContextRunner jwtSigningRunner = identityConfigRunner
            .withUserConfiguration(JwtSessionManager.class)
            .withBean(SessionRepository.class, () -> new MemorySessionRepository(FIXED_CLOCK))
            .withBean(Clock.class, () -> FIXED_CLOCK);

    @Test
    void GivenBlankJwtSecret_WhenContextStarts_ThenFailsNamingTheSecret() {
        identityConfigRunner.withPropertyValues("jwt.secret=").run(context -> {
            assertThat(context).as("a blank jwt.secret must stop the boot").hasFailed();
            assertSecretRefusedAtBinding(context.getStartupFailure());
        });
    }

    // TestPropertyValues trims what it is given, so the whitespace goes in through a property source.
    @Test
    void GivenWhitespaceOnlyJwtSecretOf32Characters_WhenContextStarts_ThenFailsNamingTheSecret() {
        identityConfigRunner
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("whitespace-secret", Map.of("jwt.secret", " ".repeat(MIN_JWT_SECRET_BYTES)))))
                .run(context -> {
                    assertThat(context).as("a jwt.secret of 32 spaces must stop the boot").hasFailed();
                    assertSecretRefusedAtBinding(context.getStartupFailure());
                });
    }

    @Test
    void GivenJwtSecretOf31Characters_WhenContextStarts_ThenFailsNamingTheKeyButNeverItsValue() {
        assertThat(SHORT_JWT_SECRET).as("test fixture: secret length").hasSize(MIN_JWT_SECRET_BYTES - 1);

        jwtSigningRunner.withPropertyValues("jwt.secret=" + SHORT_JWT_SECRET).run(context -> {
            assertThat(context).as("a jwt.secret of 31 characters must stop the boot").hasFailed();
            List<String> failureText = textOfCauseChain(context.getStartupFailure());
            assertThat(failureText).as("the startup failure, its messages and causes must never carry the secret")
                    .noneMatch(text -> text.contains(SHORT_JWT_SECRET));
            assertThat(failureText).as("the startup failure must name the key to fix")
                    .anyMatch(text -> text.contains("jwt.secret"));
        });
    }

    @Test
    void GivenJwtProperties_WhenToString_ThenTheSecretIsNotInIt() {
        assertThat(new JwtProperties(JWT_SECRET).toString()).doesNotContain(JWT_SECRET);
    }

    private static void assertSecretRefusedAtBinding(Throwable startupFailure) {
        Throwable root = NestedExceptionUtils.getRootCause(startupFailure);
        assertThat(root).isInstanceOf(BindValidationException.class);
        ValidationErrors errors = ((BindValidationException) root).getValidationErrors();
        assertThat(errors.getName()).hasToString("jwt");
        assertThat(errors.getAllErrors())
                .extracting(error -> ((FieldError) error).getField())
                .containsOnly("secret");
    }

    /**
     * Every text the startup failure carries that a log or a failure report could print: each cause's
     * {@code toString} (class and message), and the rejected value of each binding error, which
     * Spring Boot's bind-validation failure report prints as {@code Value: "..."}.
     */
    private static List<String> textOfCauseChain(Throwable failure) {
        List<String> texts = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            texts.add(cause.toString());
            if (cause instanceof BindValidationException bindValidation) {
                bindValidation.getValidationErrors().getAllErrors().stream()
                        .filter(FieldError.class::isInstance)
                        .map(error -> String.valueOf(((FieldError) error).getRejectedValue()))
                        .forEach(texts::add);
            }
        }
        return texts;
    }

    @Test
    void GivenJwtSecretOfExactly32Bytes_WhenContextStarts_ThenARecordBindsIt() {
        identityConfigRunner.withPropertyValues("jwt.secret=" + "s".repeat(MIN_JWT_SECRET_BYTES)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(ConfigurationPropertiesBean.getAll(context.getSourceApplicationContext()).values())
                    .as("a @ConfigurationProperties record must bind the jwt prefix")
                    .anySatisfy(bean -> assertThat(bean.getAnnotation().prefix()).isEqualTo("jwt"));
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
