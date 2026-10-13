package com.ticketing.system.shared.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

// Provides the BCryptPasswordEncoder bean used by Infrastructure/security/BcryptPasswordHasher.
// This is wiring config, not domain logic — it just hands the encoder to whoever asks for it.
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // Single Clock bean injected wherever code needs "now" — session manager,
    // session repository, future sweeper. Tests can override with Clock.fixed(...)
    // for deterministic time.
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
