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
 * <p>{@link #verifiesModuleBoundaries()} is enabled: the bounded-context graph is acyclic. Because the
 * modules are modelled as open, Modulith validates the module model and its cycle check here; the
 * substantive, non-exempt cycle guarantee lives in {@code HexagonalRulesTest#bounded_contexts_are_acyclic}.
 */
class ModularityTests {

    // The application module model, derived by scanning down from the Spring Boot root package.
    private final ApplicationModules modules = ApplicationModules.of(EventTicketSystemApplication.class);

    /** Regenerates the architecture diagrams + module canvases (the migration's showcase artifact). */
    @Test
    void writesModuleDocumentation() {
        new Documenter(modules).writeDocumentation(); // emits AsciiDoc + PlantUML under target/spring-modulith-docs
    }

    /**
     * Spring Modulith boundary verification. The bounded contexts are modelled as <em>open</em>
     * modules, so this validates the module model and the absence of cross-module cycles at the
     * Modulith level. The substantive, non-exempt cycle guarantee is enforced separately by
     * {@code HexagonalRulesTest#bounded_contexts_are_acyclic} (ArchUnit slices — the same engine
     * Modulith uses, run without the open-module exemption). Full type-level encapsulation
     * (closed modules exposing only named-interface ports) is documented future work.
     */
    @Test
    void verifiesModuleBoundaries() {
        modules.verify();
    }
}
