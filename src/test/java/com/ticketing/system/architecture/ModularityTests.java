package com.ticketing.system.architecture;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import com.ticketing.system.EventTicketSystemApplication;

/**
 * Spring Modulith verification of the bounded-context module boundaries.
 *
 * <p>Disabled during the incremental migration: the package tree is not yet organised into
 * per-context {@code @ApplicationModule}s, so {@code verify()} would flag the current
 * {@code Core}/{@code Infrastructure}/{@code Presentation} packages. Each migration step tightens the
 * structure; this test is enabled at Step 10, once every bounded context is a module with explicitly
 * declared allowed dependencies. Diagram/canvas generation via {@code Documenter} is also added at
 * Step 10 (it needs the {@code spring-modulith-docs} artifact).
 */
@Disabled("Enabled at Step 10, once every bounded context is an @ApplicationModule")
class ModularityTests {

    // The application module model, derived by scanning down from the Spring Boot root package.
    private final ApplicationModules modules = ApplicationModules.of(EventTicketSystemApplication.class);

    /**
     * Fails the build if any module reaches into another module's internals or a dependency cycle exists.
     */
    @Test
    void verifiesModuleBoundaries() {
        modules.verify(); // throws on illegal cross-module access or cyclic module dependencies
    }
}
