package com.ticketing.system.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The session-token signing settings, bound from {@code jwt.*} and validated at startup (ADR-003).
 *
 * <p>{@code CommittedDefaultSecretsGuard} reads {@code jwt.secret} from the environment as well, to
 * stop a boot outside {@code dev} and {@code test} that still uses the committed default.
 *
 * @param secret the HMAC key tokens are signed with, as UTF-8. At least {@value #MIN_SECRET_LENGTH}
 *               characters, since jjwt refuses an HMAC key under 256 bits, HS256's minimum; the
 *               length is checked in characters, and a character outside ASCII only adds bytes
 */
@Validated
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(@NotBlank @Size(min = JwtProperties.MIN_SECRET_LENGTH) String secret) {

    /** 256 bits in bytes: the shortest HMAC key jjwt accepts (HS256). */
    public static final int MIN_SECRET_LENGTH = 32;

    // A record's own toString prints every component, and this one is the signing key.
    @Override
    public String toString() {
        return "JwtProperties[secret=<redacted>]";
    }
}
