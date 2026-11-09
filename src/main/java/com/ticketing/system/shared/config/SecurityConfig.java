package com.ticketing.system.shared.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Spring wiring for the password encoder and the platform clock.
 *
 * <p>Wiring only, not domain logic: it hands the beans to whoever injects them.
 * {@code identity/adapter/out/security/BcryptPasswordHasher} delegates to the encoder.
 */
@Configuration
public class SecurityConfig {

    /**
     * Returns the BCrypt encoder, with its default settings, that the password hasher delegates to.
     *
     * @return the encoder bean
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Returns the one clock (ADR-008): every "now" in production code comes from this bean, in UTC, and
     * {@code HexagonalRulesTest} fails the build on any other reading of the system clock or default zone.
     * Recorded {@code LocalDateTime}s are UTC wall time only because this clock is UTC
     * ({@code ClockConfigTest}).
     *
     * <p>Unit tests pass {@code Clock.fixed(...)}; a {@code @SpringBootTest} reads this bean, which stays
     * real (ADR-024).
     *
     * @return a clock on UTC
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
