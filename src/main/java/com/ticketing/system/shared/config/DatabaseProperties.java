package com.ticketing.system.shared.config;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

/**
 * Database driver settings that bound how long a connection can hang, bound from {@code database} and
 * validated at startup (ADR-003). Applied to PostgreSQL connections only, by
 * {@link com.ticketing.system.shared.persistence.PostgresDriverSettings}.
 *
 * @param socketTimeout how long a read may wait for the database's answer before it fails. It frees the
 *     pool connection of a read in flight when the database hangs or its host disappears (SLR.6, #173),
 *     so it must be longer than the slowest legitimate query. The driver takes whole seconds, so a
 *     fraction of a second rounds up
 */
@Validated
@ConfigurationProperties(prefix = "database")
public record DatabaseProperties(@NotNull @DurationMin(seconds = 1) Duration socketTimeout) {
}
