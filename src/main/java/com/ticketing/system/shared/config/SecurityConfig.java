package com.ticketing.system.shared.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

// Provides the BCryptPasswordEncoder bean that identity/adapter/out/security/BcryptPasswordHasher delegates to.
// This is wiring config, not domain logic — it just hands the encoder to whoever asks for it.
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // The one clock (ADR-008): every "now" in production code comes from this bean, in UTC, and
    // HexagonalRulesTest fails the build on any other reading of the system clock or default zone.
    // Recorded LocalDateTimes are UTC wall time only because this clock is UTC (ClockConfigTest).
    // Unit tests pass Clock.fixed(...); a @SpringBootTest reads this bean, which stays real (ADR-024).
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
