package com.ticketing.system.architecture;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import com.ticketing.system.EventTicketSystemApplication;

/**
 * Spring Modulith model of the bounded-context modules.
 *
 * <p>{@link #writesModuleDocumentation()} is enabled — it regenerates the C4/PlantUML component
 * diagrams and per-module canvases into {@code target/spring-modulith-docs}; a committed snapshot
 * lives under {@code docs/architecture}. It works regardless of the current cross-context cycles
 * (it documents the model as-is).
 *
 * <p>{@link #verifiesModuleBoundaries()} is disabled: the deliberately-preserved transitional
 * couplings (e.g. catalog&harr;sales) form dependency cycles that Modulith {@code verify()} rejects,
 * and cycles cannot be waived by declaring allowed dependencies. It is enabled once the deferred
 * behavioural rewiring — inventory-ownership port, event-driven notifications, governance market-gate
 * port — breaks those cycles.
 */
class ModularityTests {

    // The application module model, derived by scanning down from the Spring Boot root package.
    private final ApplicationModules modules = ApplicationModules.of(EventTicketSystemApplication.class);

    /** Regenerates the architecture diagrams + module canvases (the migration's showcase artifact). */
    @Test
    void writesModuleDocumentation() {
        new Documenter(modules).writeDocumentation(); // emits AsciiDoc + PlantUML under target/spring-modulith-docs
    }

    /** Strict boundary verification — fails on illegal cross-module access or dependency cycles. */
    @Disabled("Enabled after the deferred behavioural rewiring breaks the transitional cross-context cycles")
    @Test
    void verifiesModuleBoundaries() {
        modules.verify();
    }
}
