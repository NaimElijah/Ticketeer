package com.ticketing.system.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * The session-token signing settings, bound from {@code jwt.*} and validated at startup (ADR-003).
 *
 * <p>Binding refuses only a blank secret. The minimum length is checked by {@code JwtSessionManager}
 * when the boot creates it, not by a binding constraint here, because Spring Boot's report of a
 * failed binding constraint prints the rejected value, and this value is the signing key.
 *
 * <p>{@code CommittedDefaultSecretsGuard} reads {@code jwt.secret} from the environment as well, to
 * stop a boot outside {@code dev} and {@code test} that still uses the committed default.
 *
 * @param secret the HMAC key tokens are signed with, as UTF-8. Not blank, and at least
 *               {@value #MIN_SECRET_LENGTH} characters or the boot stops, since jjwt refuses an HMAC
 *               key under 256 bits, HS256's minimum; the length is checked in characters, and a
 *               character outside ASCII only adds bytes
 */
@Validated
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(@NotBlank String secret) {

    /**
     * 256 bits in bytes: the shortest HMAC key jjwt accepts (HS256). Enforced, in characters, by
     * {@code JwtSessionManager}.
     */
    public static final int MIN_SECRET_LENGTH = 32;

    // A record's own toString prints every component, and this one is the signing key.
    @Override
    public String toString() {
        return "JwtProperties[secret=<redacted>]";
    }
}
