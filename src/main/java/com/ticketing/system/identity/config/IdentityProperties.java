package com.ticketing.system.identity.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

/**
 * Identity settings, bound from {@code identity.*} and validated at startup (ADR-003).
 *
 * <p>{@code platformAdminUsername} is bound from {@code identity.platform-admin-username}, which
 * {@code application.yml} sets to {@code ${platform.admin.username}}. That keeps the default admin's
 * name reserved without identity importing governance's {@code PlatformAdminProperties}, and it
 * stays unvalidated here because that record already rejects a blank name.
 *
 * @param reservedUsernames usernames no member may register (UC-11, #76); blank entries are ignored
 * @param platformAdminUsername the default System Admin's username, which is always reserved too
 */
@Validated
@ConfigurationProperties(prefix = "identity")
public record IdentityProperties(@NotNull List<String> reservedUsernames, String platformAdminUsername) {
}
