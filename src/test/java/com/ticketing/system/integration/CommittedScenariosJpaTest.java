package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.bootstrap.config.SeedProperties;
import com.ticketing.system.bootstrap.dev.seed.RepoCleaner;
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
import com.ticketing.system.shared.config.PlatformTimeProperties;
import com.ticketing.system.ui.dev.DevUserSeeder;

/**
 * Every committed scenario runs clean through the real application services under fail-fast: each
 * step passes, and each {@code expect-error} is rejected with the exception it names (#187).
 *
 * <p>{@link ScenarioRunner} is not a bean under {@code test}, so the test builds one from the
 * context's beans. The default admin's configured password is not the committed one, as on any run
 * outside {@code dev} and {@code test}, so a scenario that signs in with a password written in the
 * file fails here. The scenarios register users and companies, so this class has its own context and
 * discards it afterwards.
 */
@SpringBootTest(properties = "platform.admin.password=" + CommittedScenariosJpaTest.CONFIGURED_ADMIN_PASSWORD)
@ActiveProfiles("test")
@DirtiesContext
class CommittedScenariosJpaTest {

    // ---- Defect 4: every committed scenario's expectations hold (#187) ----

    static final String CONFIGURED_ADMIN_PASSWORD = "configured-admin-password-not-the-default";

    @Autowired private AuthenticationService authenticationService;
    @Autowired private CompanyManagementService companyManagementService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private ReservationService reservationService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private CatalogService catalogService;
    @Autowired private MessagingService messagingService;
    @Autowired private UserRepository userRepository;
    @Autowired private RepoCleaner repoCleaner;
    @Autowired private ObjectProvider<DevUserSeeder> devUserSeeder;
    @Autowired private SystemAdminService systemAdminService;
    @Autowired private SessionManager sessionManager;
    @Autowired private ResourceLoader resourceLoader;
    @Autowired private Environment environment;
    @Autowired private java.time.Clock clock;
    @Autowired private PlatformAdminProperties platformAdmin;
    @Autowired private PlatformTimeProperties platformTime;
    @Value("${spring.datasource.url}") private String datasourceUrl;

    // Under test no runner initialises the platform; the scenario runner opens the market itself.
    @BeforeEach
    void initialisePlatform() {
        systemAdminService.initializePlatform();
    }

    @ParameterizedTest(name = "{0}.scenario")
    @ValueSource(strings = {"demo", "review"})
    void GivenACommittedScenario_WhenReseededUnderFailFast_ThenEveryStepAndExpectationHolds(String scenario) {
        ScenarioRunner runner = runnerFor("classpath:scenarios/" + scenario + ".scenario");

        assertDoesNotThrow(() -> runner.run(null),
                scenario + ".scenario must run clean: a failed step or a mismatched expect-error stops a fail-fast boot");
    }

    private ScenarioRunner runnerFor(String scenarioLocation) {
        return new ScenarioRunner(
                authenticationService,
                companyManagementService,
                eventManagementService,
                reservationService,
                checkoutService,
                catalogService,
                messagingService,
                userRepository,
                repoCleaner,
                devUserSeeder,
                systemAdminService,
                sessionManager,
                resourceLoader,
                environment,
                clock,
                new SeedProperties(SeedProperties.Mode.RESEED, true, false, scenarioLocation),
                platformAdmin,
                platformTime,
                datasourceUrl);
    }
}
