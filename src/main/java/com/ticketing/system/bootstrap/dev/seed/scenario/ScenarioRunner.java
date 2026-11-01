package com.ticketing.system.bootstrap.dev.seed.scenario;

import com.ticketing.system.bootstrap.DatasourceUrls;
import com.ticketing.system.bootstrap.config.SeedProperties;
import com.ticketing.system.governance.config.PlatformAdminProperties;
import com.ticketing.system.shared.config.PlatformTimeProperties;
import com.ticketing.system.shared.dto.MarketControlRequestDTO;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.bootstrap.dev.seed.DemoClock;
import com.ticketing.system.bootstrap.dev.seed.RepoCleaner;
import com.ticketing.system.bootstrap.dev.seed.SeedHarness;
import com.ticketing.system.bootstrap.dev.seed.SeedReport;
import com.ticketing.system.ui.dev.DevUserSeeder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Boots the system from an editable scenario file (#366). Reads {@code seed.scenario}, parses it, and
 * runs each line through the real application services via {@link ScenarioOps}, classifying every step
 * in a {@link SeedReport}. Because each line is a real service call, the run doubles as a smoke test.
 *
 * <p>Present on every non-{@code test} profile but <strong>inert by default</strong>
 * ({@code seed.mode=off}) — it never seeds or wipes prod/cloud unless explicitly asked. Works against
 * whatever database the run is configured with: dev's in-memory H2 or a persistent PostgreSQL.
 *
 * <p>{@code seed.mode}:
 * <ul>
 *   <li>{@code off} (default) — do nothing.</li>
 *   <li>{@code reseed} — run the scenario, opening the market first so its purchases can run. Before any
 *       step, it looks up the users the scenario registers (by username, honouring a line's
 *       {@code username=}); if one already exists, the scenario has run against this database before, so
 *       no step runs, a WARN points at {@code reset}, and the boot goes on. Users the scenario does not
 *       register, such as {@code DevUserSeeder}'s, do not count.</li>
 *   <li>{@code wipe} — delete all business data, then stop.</li>
 *   <li>{@code reset} — {@code wipe} then reseed.</li>
 *   <li>{@code ask} — interactive startup menu: choose the mode <em>and</em> the scenario on the
 *       console at boot, then run that choice.</li>
 * </ul>
 * {@code wipe}/{@code reset} are destructive and gated by an ENV FLAG, not a console prompt: they only
 * proceed when {@code seed.assume-yes=true} (e.g. {@code SEED_ASSUME_YES=true}); otherwise they log what
 * would be deleted plus the command to enable it, and refuse. This holds whether the mode came from
 * {@code SEED_MODE} directly or was chosen in the {@code ask} menu — picking a destructive mode is not by
 * itself sufficient. The wipe keeps the bootstrap admin (see {@code JpaRepoCleaner}). The {@code ask} menu
 * reads stdin only to choose mode/scenario; with no terminal it falls back to {@code off} (safe).
 * {@code seed.fail-fast=true} (the default outside {@code dev}) stops the scenario at the first unexpected
 * error and fails startup, as does a missing or unreadable scenario file (V3 2.a.ii, ADR-003).
 */
@Component
@Profile("!test")
@Order(2)
@Slf4j
public class ScenarioRunner implements ApplicationRunner {

    // Mirrors SystemAdminService.DEFAULT_ADMIN_ID — the bootstrap admin used to open the market.
    private static final int DEFAULT_ADMIN_ID = 1;
    private static final List<String> MODES = List.of("off", "reseed", "wipe", "reset");
    private static final List<String> SCENARIOS = List.of("demo", "review");

    private final AuthenticationService authenticationService;
    private final CompanyManagementService companyManagementService;
    private final EventManagementService eventManagementService;
    private final ReservationService reservationService;
    private final CheckoutService checkoutService;
    private final CatalogService catalogService;
    private final MessagingService messagingService;
    private final UserRepository userRepository;
    private final RepoCleaner repoCleaner;
    private final ObjectProvider<DevUserSeeder> devUserSeeder;
    private final SystemAdminService systemAdminService;
    private final SessionManager sessionManager;
    private final ResourceLoader resourceLoader;
    private final Environment environment;
    private final java.time.Clock clock;
    private final SeedProperties.Mode seedMode;
    private final boolean failFast;
    private final boolean assumeYes;
    private final String scenarioLocation;
    private final PlatformAdminProperties platformAdmin;
    private final ZoneId timeZone;
    // Logged and shown on the console, so it is held without its query string, which can carry credentials.
    private final String datasourceUrl;

    // One reader for the whole interactive session: a fresh BufferedReader per prompt would
    // discard input the previous one read-ahead and buffered.
    private BufferedReader consoleReader;

    public ScenarioRunner(
            AuthenticationService authenticationService,
            CompanyManagementService companyManagementService,
            EventManagementService eventManagementService,
            ReservationService reservationService,
            CheckoutService checkoutService,
            CatalogService catalogService,
            MessagingService messagingService,
            UserRepository userRepository,
            RepoCleaner repoCleaner,
            ObjectProvider<DevUserSeeder> devUserSeeder,
            SystemAdminService systemAdminService,
            SessionManager sessionManager,
            ResourceLoader resourceLoader,
            Environment environment,
            java.time.Clock clock,
            SeedProperties seed,
            PlatformAdminProperties platformAdmin,
            PlatformTimeProperties platformTime,
            @Value("${spring.datasource.url:unknown}") String datasourceUrl) {
        this.authenticationService = authenticationService;
        this.companyManagementService = companyManagementService;
        this.eventManagementService = eventManagementService;
        this.reservationService = reservationService;
        this.checkoutService = checkoutService;
        this.catalogService = catalogService;
        this.messagingService = messagingService;
        this.userRepository = userRepository;
        this.repoCleaner = repoCleaner;
        this.devUserSeeder = devUserSeeder;
        this.systemAdminService = systemAdminService;
        this.sessionManager = sessionManager;
        this.resourceLoader = resourceLoader;
        this.environment = environment;
        this.clock = clock;
        this.seedMode = seed.mode();
        this.failFast = seed.failFast();
        this.assumeYes = seed.assumeYes();
        this.scenarioLocation = seed.scenario();
        this.platformAdmin = platformAdmin;
        this.timeZone = platformTime.timeZone();
        this.datasourceUrl = DatasourceUrls.withoutQuery(datasourceUrl);
    }

    @Override
    public void run(ApplicationArguments args) {
        executeMode(seedMode, scenarioLocation);
    }

    /** Runs one mode against one scenario. Shared by the config-driven and interactive paths. */
    private void executeMode(SeedProperties.Mode mode, String scenario) {
        switch (mode) {
            case OFF -> log.info("seed.mode=off — no seeding");
            case RESEED -> {
                List<ScenarioCommand> commands = readScenario(scenario);
                if (registersAnExistingUser(commands)) {
                    log.warn("seed.mode=reseed — skipping scenario '{}': a user it registers already exists in "
                            + "this database, so it has run here before. To replay it, wipe and reseed with "
                            + "seed.mode=reset and seed.assume-yes=true.", scenario);
                } else {
                    log.info("seed.mode=reseed — seeding");
                    ensureMarketOpen();
                    runScenario(scenario, commands);
                }
            }
            case WIPE -> {
                if (destructiveAllowed("wipe")) {
                    repoCleaner.clearAll();
                    log.info("seed.mode=wipe — done; database cleared");
                }
            }
            case RESET -> {
                if (destructiveAllowed("reset")) {
                    repoCleaner.clearAll();
                    ensureMarketOpen();
                    devUserSeeder.ifAvailable(seeder -> seeder.run(null));
                    runScenario(scenario, readScenario(scenario));
                    log.info("seed.mode=reset — done; database wiped and reseeded");
                }
            }
            case ASK -> runInteractive();
        }
    }

    /** Interactive startup menu ({@code seed.mode=ask}): pick the mode, then the scenario, then run it. */
    private void runInteractive() {
        System.out.println();
        System.out.println("  === SEEDER — profiles: " + String.join(",", environment.getActiveProfiles())
                + " — database: " + datasourceUrl + " ===");
        String mode = askChoice("Mode", MODES, "off");
        // A typo at the console is skipped, not fatal: binding already rejected any unknown configured mode.
        if (!MODES.contains(mode)) {
            log.warn("seed mode '{}' not recognised ({}) — skipping", mode, String.join(" | ", MODES));
            return;
        }
        String scenario = scenarioLocation;
        if (mode.equals("reseed") || mode.equals("reset")) {
            scenario = "classpath:scenarios/" + askChoice("Scenario", SCENARIOS, "demo") + ".scenario";
        }
        executeMode(SeedProperties.Mode.valueOf(mode.toUpperCase(Locale.ROOT)), scenario);
    }

    /** Prompts with a list of options and a default; returns the lowercased answer, or the default on empty/EOF. */
    private String askChoice(String label, List<String> options, String dflt) {
        System.out.println();
        System.out.println("  " + label + "? " + options + "  [default: " + dflt + "]");
        System.out.print("  > ");
        System.out.flush();
        try {
            String line = readConsoleLine();
            if (line == null) {
                return dflt;
            }
            String value = line.trim().toLowerCase();
            return value.isEmpty() ? dflt : value;
        } catch (IOException e) {
            return dflt;
        }
    }

    /** Opens the trading market via the real admin path so the scenario's reserve/checkout steps work. */
    private void ensureMarketOpen() {
        try {
            String adminToken = sessionManager.generateAdminToken(DEFAULT_ADMIN_ID, platformAdmin.username());
            systemAdminService.openMarket(new MarketControlRequestDTO("OPEN", "seed auto-open", adminToken));
            log.info("seed: market opened so seeded purchases can run");
        } catch (RuntimeException e) {
            log.warn("seed: could not open the market ({}); the scenario's checkout steps may fail", e.getMessage());
        }
    }

    /**
     * Safety gate before a destructive wipe. The opt-in is an ENV FLAG ({@code seed.assume-yes=true}),
     * NOT a console prompt — so the gate never depends on stdin reaching the app (which it may not under
     * a forked {@code spring-boot:run}). Applies identically whether the mode came from {@code SEED_MODE}
     * directly or from the {@code ask} menu: choosing {@code wipe}/{@code reset} is not enough on its own.
     * When the flag is unset it logs exactly what would be deleted, the command to enable it, and refuses.
     */
    private boolean destructiveAllowed(String mode) {
        if (assumeYes) {
            log.warn("seed.mode={} — seed.assume-yes is set; wiping ALL business data (admin kept) from {} (profiles: {})",
                    mode, datasourceUrl, String.join(",", environment.getActiveProfiles()));
            return true;
        }
        log.warn("seed.mode={} REFUSED — a destructive wipe needs an explicit opt-in (it was not given).", mode);
        log.warn("  Would DELETE ALL business data (events, users, companies, orders, tickets, messages),");
        log.warn("  keeping only the platform admin, from:");
        log.warn("    profiles : {}", String.join(",", environment.getActiveProfiles()));
        log.warn("    database : {}", datasourceUrl);
        log.warn("  To proceed, set the flag, e.g.:  SEED_MODE={} SEED_ASSUME_YES=true ./run-supabase.sh", mode);
        return false;
    }

    private String readConsoleLine() throws IOException {
        if (consoleReader == null) {
            consoleReader = new BufferedReader(new InputStreamReader(System.in));
        }
        return consoleReader.readLine();
    }

    // Only the scenario's own users count: DevUserSeeder may have registered others earlier in this boot.
    private boolean registersAnExistingUser(List<ScenarioCommand> commands) {
        return commands.stream()
                .filter(cmd -> cmd.op().equals(ScenarioOps.REGISTER_OP))
                .map(ScenarioOps::registeredUsername)
                .filter(Objects::nonNull)
                .anyMatch(userRepository::existsByUsername);
    }

    // V3 2.a.ii / ADR-003: a requested initial-state file is part of initialization, so a missing or
    // unreadable file propagates and stops the boot.
    private List<ScenarioCommand> readScenario(String scenario) {
        Resource resource = resourceLoader.getResource(scenario);
        if (!resource.exists()) {
            throw new IllegalStateException("Initial-state file not found: " + scenario + " (seed.scenario)");
        }
        String text;
        try (var in = resource.getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read initial-state file " + scenario, e);
        }
        return new ScenarioParser().parse(text);
    }

    // A failed step under fail-fast propagates and stops the boot (V3 2.a.ii, ADR-003).
    private void runScenario(String scenario, List<ScenarioCommand> commands) {
        SeedReport report = new SeedReport();
        SeedHarness harness = new SeedHarness(report, failFast);
        ScenarioContext ctx = new ScenarioContext();
        ScenarioOps ops = new ScenarioOps(
            authenticationService, companyManagementService, eventManagementService,
            reservationService, checkoutService, catalogService, messagingService,
            userRepository, new DemoClock(clock), timeZone, platformAdmin);

        log.info("running scenario '{}' ({} operations)", scenario, commands.size());
        try {
            for (ScenarioCommand cmd : commands) {
                ops.execute(cmd, ctx, harness);
            }
        } finally {
            String summary = report.render();
            if (report.hasFailures()) {
                log.warn("scenario '{}' finished WITH FAILURES — see report below.{}", scenario, summary);
            } else {
                log.info("scenario '{}' finished clean.{}", scenario, summary);
            }
        }
    }
}
