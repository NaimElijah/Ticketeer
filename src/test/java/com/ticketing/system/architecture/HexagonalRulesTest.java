package com.ticketing.system.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * ArchUnit hexagonal-layering rules for the bounded-context modules.
 *
 * <p>The rules are driven through the Jupiter engine (importing production classes with
 * {@link ClassFileImporter} and calling {@code check(...)}) rather than ArchUnit's {@code @ArchTest}
 * field engine, because that engine is not reliably enforced by this project's Surefire setup — its
 * rules silently reported zero executed tests and a deliberately-failing rule did not fail the build.
 * Plain {@code @Test} methods are guaranteed to run under Surefire and to fail on a violation.
 *
 * <p>Alongside the hexagonal-layering invariants, {@link #bounded_contexts_are_acyclic()} enforces
     * that the bounded-context modules form a directed acyclic graph. This is the substantive cycle
     * guarantee: it uses ArchUnit slices (the same cycle engine Spring Modulith uses) run directly here,
     * so it applies even though the modules are declared <em>open</em> (open modules are exempt from
     * Modulith's own cycle check).
 */
class HexagonalRulesTest {

    // Production classes only (tests excluded), imported once and reused by every rule below.
    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.ticketing.system");

    /**
     * The domain is the innermost hexagon: a context's {@code domain} package must not depend on the
     * Spring or Vaadin frameworks, nor outward on the {@code application} or {@code adapter} layers.
     * {@code jakarta.persistence} is deliberately allowed — the aggregates are the JPA entities
     * (the "persistence-annotated domain" decision), which is a spec dependency, not a framework one.
     */
    @Test
    void domain_is_free_of_framework_and_outer_layers() {
        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",   // no Spring in the domain
                        "com.vaadin..",             // no Vaadin in the domain
                        "..application..",           // domain must not reach up into the application layer
                        "..adapter..")               // domain must not reach out into adapters
                .check(PRODUCTION_CLASSES);
    }

    /**
     * The application layer (use-case services + ports) drives the domain through ports; it must never
     * depend on a concrete {@code adapter} (adapters implement the outbound ports and are injected by
     * Spring — the dependency arrow points inward, from adapter to application, never the reverse).
     */
    @Test
    void application_does_not_depend_on_adapters() {
        noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAPackage("..adapter..")
                .check(PRODUCTION_CLASSES);
    }

    /**
     * The Vaadin {@code ui} module is the single driving (inbound) adapter: it may depend on the
     * backend contexts' application APIs, but no backend context may depend back on {@code ui}. This
     * is the invariant that keeps the UI a leaf of the dependency graph.
     */
    @Test
    void no_backend_context_depends_on_the_ui() {
        noClasses()
                .that().resideInAnyPackage(
                        "com.ticketing.system.identity..", "com.ticketing.system.organization..",
                        "com.ticketing.system.catalog..", "com.ticketing.system.sales..",
                        "com.ticketing.system.messaging..", "com.ticketing.system.notifications..",
                        "com.ticketing.system.governance..", "com.ticketing.system.shared..")
                .should().dependOnClassesThat().resideInAPackage("com.ticketing.system.ui..")
                .check(PRODUCTION_CLASSES);
    }

    /**
     * The bounded contexts must form a directed acyclic graph: no two top-level modules may depend on
     * each other, directly or transitively. This is the substantive guarantee that Spring Modulith's
     * {@code verify()} would give for cycles — enforced here with ArchUnit slices because it analyses
     * real bytecode package dependencies, independent of Modulith's module-encapsulation model (under
     * which OPEN modules are exempt from cycle checks).
     */
    
    @Test
    void bounded_contexts_are_acyclic() {
        slices()
                .matching("com.ticketing.system.(*)..")   // one slice per top-level module (catalog, sales, ...)
                .should().beFreeOfCycles()
                .check(PRODUCTION_CLASSES);
    }
}
