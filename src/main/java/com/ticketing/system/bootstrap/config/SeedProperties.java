package com.ticketing.system.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Initial-state seeding (V3 2.a.ii), bound from {@code seed.*} and validated at startup (ADR-003).
 * An unknown {@code mode} fails binding instead of being skipped. SEED.md describes each mode.
 *
 * @param mode      what the seeder does at boot
 * @param failFast  whether the first unexpected failure in the scenario aborts the run
 * @param assumeYes the explicit opt-in that destructive modes ({@code wipe}, {@code reset}) require
 * @param scenario  Spring resource location of the {@code .scenario} file to replay
 */
@Validated
@ConfigurationProperties(prefix = "seed")
public record SeedProperties(@NotNull Mode mode, boolean failFast, boolean assumeYes, @NotBlank String scenario) {

    /** What the seeder does at boot. */
    public enum Mode { OFF, RESEED, WIPE, RESET, ASK }
}
