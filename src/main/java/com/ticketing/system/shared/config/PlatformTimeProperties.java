package com.ticketing.system.shared.config;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

/**
 * The platform's home time zone, bound from {@code platform.time-zone} and validated at startup
 * (ADR-003): an unknown IANA id stops the boot instead of falling back to the JVM's zone.
 *
 * <p>It is the zone a new event defaults to, the zone seeded events are created in, and the zone
 * the UI shows recorded timestamps and "today" in (ADR-008). It never changes the zone of an
 * existing event.
 *
 * @param timeZone an IANA zone id such as {@code Asia/Jerusalem}
 */
@Validated
@ConfigurationProperties(prefix = "platform")
public record PlatformTimeProperties(@NotNull ZoneId timeZone) {
}
