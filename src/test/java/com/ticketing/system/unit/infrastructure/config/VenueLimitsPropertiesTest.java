package com.ticketing.system.unit.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.bind.validation.ValidationErrors;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.validation.FieldError;

import com.ticketing.system.catalog.config.VenueLimitsProperties;

/**
 * #185: the venue size limits {@code configureVenueMap} enforces are init parameters bound from
 * {@code catalog.venue-limits.*} (ADR-003). The shipped defaults match the venue editor's own caps
 * (99 rows x 500 seats per zone, 1,000,000 standing places, a 12 x 12 grid) plus a per-map seat total.
 */
class VenueLimitsPropertiesTest {

    private static final String PREFIX = "catalog.venue-limits";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(LimitsOnly.class);

    @EnableConfigurationProperties(VenueLimitsProperties.class)
    static class LimitsOnly {
    }

    @Test
    void GivenShippedDefaults_WhenContextStarts_ThenTheVenueLimitsBindToTheEditorCaps() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            VenueLimitsProperties limits = context.getBean(VenueLimitsProperties.class);
            assertThat(limits.maxGridSize()).as("grid rows and cols").isEqualTo(12);
            assertThat(limits.maxSeatsPerZone()).as("99 rows x 500 seats").isEqualTo(49_500);
            assertThat(limits.maxStandingCapacity()).as("standing places per zone").isEqualTo(1_000_000);
            assertThat(limits.maxSeatsPerMap()).as("seats across the whole map").isEqualTo(100_000);
        });
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "max-grid-size,         0,  maxGridSize",
            "max-seats-per-zone,    0,  maxSeatsPerZone",
            "max-standing-capacity, -1, maxStandingCapacity",
            "max-seats-per-map,     0,  maxSeatsPerMap",
    })
    void GivenNonPositiveLimit_WhenContextStarts_ThenFailsNamingTheField(String key, String value, String field) {
        runner.withPropertyValues(PREFIX + "." + key + "=" + value).run(context -> {
            assertThat(context).as("a non-positive venue limit must stop the boot").hasFailed();
            Throwable root = NestedExceptionUtils.getRootCause(context.getStartupFailure());
            assertThat(root).isInstanceOf(BindValidationException.class);
            ValidationErrors errors = ((BindValidationException) root).getValidationErrors();
            assertThat(errors.getName()).hasToString(PREFIX);
            assertThat(errors.getAllErrors())
                    .extracting(error -> ((FieldError) error).getField())
                    .containsExactly(field);
        });
    }
}
