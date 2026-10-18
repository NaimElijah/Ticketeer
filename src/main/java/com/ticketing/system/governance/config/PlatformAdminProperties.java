package com.ticketing.system.governance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * Credentials of the default System Admin created at initialization when none exists (UC-1,
 * I.1.4), bound from {@code platform.admin.*} and validated at startup (ADR-003).
 *
 * @param username the default admin's username
 * @param password the default admin's plain-text password, hashed before it is stored
 */
@Validated
@ConfigurationProperties(prefix = "platform.admin")
public record PlatformAdminProperties(@NotBlank String username, @NotBlank String password) {

    /** Redacts the password, so logging this record never leaks it. */
    @Override
    public String toString() {
        return "PlatformAdminProperties[username=" + username + ", password=***]";
    }
}
