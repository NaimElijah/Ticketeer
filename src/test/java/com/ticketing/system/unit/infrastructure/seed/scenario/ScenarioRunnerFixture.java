package com.ticketing.system.unit.infrastructure.seed.scenario;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import com.ticketing.system.bootstrap.config.SeedProperties;
import com.ticketing.system.bootstrap.dev.seed.RepoCleaner;
import com.ticketing.system.bootstrap.dev.seed.scenario.ScenarioRunner;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.governance.config.PlatformAdminProperties;
import com.ticketing.system.identity.adapter.out.persistence.MemoryUserRepository;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.shared.config.PlatformTimeProperties;
import com.ticketing.system.ui.dev.DevUserSeeder;

/**
 * Builds a {@link ScenarioRunner} for the seed tests: the application services a scenario calls are
 * mocks, as in {@link ScenarioRunnerTest}, and the users live in a real {@link MemoryUserRepository},
 * so a test can arrange users that "already exist" without stubbing how the runner looks them up.
 *
 * <p>Set the mutable fields before calling {@link #runner}; each test makes its own fixture.
 */
final class ScenarioRunnerFixture {

    static final String DEFAULT_DATASOURCE_URL = "jdbc:h2:mem:scenario-runner-fixture";
    static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    final AuthenticationService authenticationService = mock(AuthenticationService.class);
    final CompanyManagementService companyManagementService = mock(CompanyManagementService.class);
    final EventManagementService eventManagementService = mock(EventManagementService.class);
    final ReservationService reservationService = mock(ReservationService.class);
    final CheckoutService checkoutService = mock(CheckoutService.class);
    final CatalogService catalogService = mock(CatalogService.class);
    final MessagingService messagingService = mock(MessagingService.class);
    final UserRepository users = new MemoryUserRepository();
    final RepoCleaner repoCleaner = mock(RepoCleaner.class);
    final SystemAdminService systemAdminService = mock(SystemAdminService.class);
    final SessionManager sessionManager = mock(SessionManager.class);
    final MockEnvironment environment = new MockEnvironment();

    PlatformAdminProperties platformAdmin = new PlatformAdminProperties("admin", "admin");
    String datasourceUrl = DEFAULT_DATASOURCE_URL;
    boolean assumeYes = false;

    @SuppressWarnings("unchecked")
    ScenarioRunner runner(SeedProperties.Mode mode, boolean failFast, String scenario) {
        ObjectProvider<DevUserSeeder> noDevUserSeeder = mock(ObjectProvider.class);
        return new ScenarioRunner(
                authenticationService,
                companyManagementService,
                eventManagementService,
                reservationService,
                checkoutService,
                catalogService,
                messagingService,
                users,
                repoCleaner,
                noDevUserSeeder,
                systemAdminService,
                sessionManager,
                new DefaultResourceLoader(),
                environment,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SeedProperties(mode, failFast, assumeYes, scenario),
                platformAdmin,
                new PlatformTimeProperties(ZoneId.of("Asia/Jerusalem")),
                datasourceUrl);
    }

    /** No scenario step reached an application service a scenario operation calls. */
    void verifyNoScenarioStepRan() {
        verifyNoInteractions(authenticationService, companyManagementService, eventManagementService,
                reservationService, checkoutService, catalogService, messagingService);
    }

    /** Writes {@code content} to a scenario file in {@code dir} and returns its {@code file:} location. */
    static String scenarioFile(Path dir, String content) throws IOException {
        Path file = dir.resolve("test.scenario");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return "file:" + file;
    }
}
