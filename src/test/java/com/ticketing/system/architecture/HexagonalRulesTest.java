package com.ticketing.system.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * ArchUnit hexagonal-layering rules, executed as plain JUnit 5 tests.
 *
 * <p>The rules are driven through the Jupiter engine (importing production classes with
 * {@link ClassFileImporter} and calling {@code check(...)}) rather than ArchUnit's {@code @ArchTest}
 * field engine, because that engine is not reliably enforced by this project's Surefire setup — its
 * rules silently reported zero executed tests and a deliberately-failing rule did not fail the build.
 * Plain {@code @Test} methods are guaranteed to run under Surefire and to fail on a violation.
 *
 * <p>During the migration this holds only invariants already true of the legacy
 * {@code Core}/{@code Infrastructure}/{@code Presentation} layout; the full per-context port/adapter
 * rules are added as each context is relaid out and finalised at Step 10.
 */
class HexagonalRulesTest {

    // Production classes only (tests excluded), imported once and reused by every rule below.
    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.ticketing.system");

    /**
     * The domain is the innermost hexagon: it must never depend outward on the infrastructure or
     * presentation adapters (adapters depend inward onto the domain, never the reverse). This holds
     * today and must be preserved by every migration step.
     */
    @Test
    void domain_does_not_depend_on_outer_layers() {
        noClasses()
                .that().resideInAPackage("..Core.Domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..Infrastructure..", "..Presentation..")
                .check(PRODUCTION_CLASSES); // fails the test on any violation
    }
}
