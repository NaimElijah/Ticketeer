package com.ticketing.system.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import com.ticketing.system.EventTicketSystemApplication;

/**
 * Spring Modulith model of the bounded-context modules.
 *
 * <p>{@link #GivenTheApplicationModules_WhenWriteModuleDocumentation_ThenTheComponentDiagramNamesEveryModule()}
 * regenerates the C4/PlantUML component diagram and the per-module canvases into
 * {@code target/spring-modulith-docs}; a hand-copied snapshot lives under {@code docs/architecture}.
 *
 * <p>{@link #GivenTheOpenApplicationModules_WhenVerifyModuleBoundaries_ThenReportsNoViolations()} checks
 * little today. Every module is declared {@code Type.OPEN}: an open module exposes all its types, and
 * Modulith's cycle check assigns no slice to a class of an open module, so {@code verify()} looks for
 * neither encapsulation breaches nor cycles. The module graph is enforced by
 * {@code HexagonalRulesTest} instead (ADR-011): its module map and
 * {@code GivenProductionCode_WhenScanned_ThenBoundedContextsAreAcyclic}. {@code verify()} starts to
 * matter once #55 closes the modules.
 */
class ModularityTests {

    // The top-level packages under the application's root, one module each (.claude/CLAUDE.md, Architecture).
    private static final String[] MODULES = {"shared", "identity", "organization", "catalog", "sales",
            "messaging", "notifications", "governance", "reporting", "ui", "bootstrap"};

    // Documenter's default output folder in a Maven build, which .claude/CLAUDE.md says to copy from.
    private static final Path COMPONENT_DIAGRAM = Path.of("target", "spring-modulith-docs", "components.puml");

    // The application module model, derived by scanning down from the Spring Boot root package.
    private final ApplicationModules modules = ApplicationModules.of(EventTicketSystemApplication.class);

    @Test
    void GivenTheApplicationRootPackage_WhenTheModulesAreDetected_ThenEachTopLevelPackageIsOneModule() {
        assertThat(modules.stream().map(ApplicationModule::getName)).containsExactlyInAnyOrder(MODULES);
    }

    // The diagram is deleted first, so a file left by an earlier run cannot pass for this one's output.
    @Test
    void GivenTheApplicationModules_WhenWriteModuleDocumentation_ThenTheComponentDiagramNamesEveryModule()
            throws IOException {
        Files.deleteIfExists(COMPONENT_DIAGRAM);

        new Documenter(modules).writeDocumentation();

        assertThat(COMPONENT_DIAGRAM).as("the component diagram Documenter writes").exists();
        String diagram = Files.readString(COMPONENT_DIAGRAM);
        assertThat(modules.stream()).allSatisfy(module ->
                assertThat(diagram).as("the component diagram").contains(module.getDisplayName()));
    }

    /**
     * Spring Modulith's own verification. With every module open it reports no cycle and no use of another
     * module's internal type; see the class comment for what {@code HexagonalRulesTest} checks instead.
     */
    @Test
    void GivenTheOpenApplicationModules_WhenVerifyModuleBoundaries_ThenReportsNoViolations() {
        modules.verify();
    }
}
