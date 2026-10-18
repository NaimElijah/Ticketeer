package com.ticketing.system.unit.infrastructure.seed.scenario;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import com.ticketing.system.bootstrap.config.SeedProperties;
import com.ticketing.system.bootstrap.dev.seed.RepoCleaner;
import com.ticketing.system.bootstrap.dev.seed.SeedHarness;
import com.ticketing.system.bootstrap.dev.seed.scenario.ScenarioRunner;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.governance.config.PlatformAdminProperties;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.ui.dev.DevUserSeeder;

/**
 * V3 2.a.ii / ADR-003: a requested initial-state file that is missing, or whose step fails under
 * fail-fast, stops the boot. Services are mocks: an unknown operation fails before reaching them.
 */
class ScenarioRunnerTest {

    private static final String UNKNOWN_OPERATION = "no-such-operation u1\n";

    @TempDir
    Path dir;

    @Test
    void GivenMissingScenarioFile_WhenRun_ThenThrowsSoBootFails() {
        ScenarioRunner runner = runner(SeedProperties.Mode.RESEED, true, "file:" + dir.resolve("absent.scenario"));

        assertThrows(IllegalStateException.class, () -> runner.run(null));
    }

    @Test
    void GivenFailingStepUnderFailFast_WhenRun_ThenThrowsSoBootFails() throws IOException {
        ScenarioRunner runner = runner(SeedProperties.Mode.RESEED, true, scenarioFile(UNKNOWN_OPERATION));

        assertThrows(SeedHarness.SeedAbortException.class, () -> runner.run(null));
    }

    @Test
    void GivenFailingStepWithoutFailFast_WhenRun_ThenBootContinues() throws IOException {
        ScenarioRunner runner = runner(SeedProperties.Mode.RESEED, false, scenarioFile(UNKNOWN_OPERATION));

        assertDoesNotThrow(() -> runner.run(null));
    }

    private String scenarioFile(String content) throws IOException {
        Path file = dir.resolve("broken.scenario");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return "file:" + file;
    }

    @SuppressWarnings("unchecked")
    private static ScenarioRunner runner(SeedProperties.Mode mode, boolean failFast, String scenario) {
        return new ScenarioRunner(
                mock(AuthenticationService.class),
                mock(CompanyManagementService.class),
                mock(EventManagementService.class),
                mock(ReservationService.class),
                mock(CheckoutService.class),
                mock(CatalogService.class),
                mock(MessagingService.class),
                mock(UserRepository.class),
                mock(RepoCleaner.class),
                mock(ObjectProvider.class),
                mock(SystemAdminService.class),
                mock(SessionManager.class),
                new DefaultResourceLoader(),
                new MockEnvironment(),
                Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC),
                new SeedProperties(mode, failFast, false, scenario),
                new PlatformAdminProperties("admin", "admin"),
                "jdbc:h2:mem:scenario-runner-test");
    }
}
