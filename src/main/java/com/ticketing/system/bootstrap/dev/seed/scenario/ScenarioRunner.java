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
import org.springframework.core.env.Profiles;
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
import java.util.Optional;

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
 *   <li>{@code reset} — read the scenario, {@code wipe}, then run it. A scenario that cannot be read or
 *       parsed stops the boot before anything is deleted, and the scenario runs even if its users
 *       existed, since the wipe removed them.</li>
 *   <li>{@code ask} — interactive startup menu: choose the mode <em>and</em> the scenario on the
 *       console at boot, then run that choice.</li>
 * </ul>
 * {@code wipe}/{@code reset} are destructive and gated by an ENV FLAG, not a console prompt: they only
 * proceed when {@code seed.assume-yes=true} (e.g. {@code SEED_ASSUME_YES=true}); otherwise they log what
 * would be deleted plus the command to enable it, and refuse. This holds whether the mode came from
 * {@code SEED_MODE} directly or was chosen in the {@code ask} menu — picking a destructive mode is not by
 * itself sufficient. The wipe keeps the bootstrap admin (see {@code JpaRepoCleaner}). The {@code ask} menu
 * reads stdin only to choose mode/scenario; with no terminal it falls back to {@code off} (safe). A
 * scenario answer that names no committed scenario is asked again; if the console ends after one, the
 * boot stops with the valid choices named.
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
    private static final String DEFAULT_SCENARIO = "demo";
    private static final String SCENARIO_QUESTION = "Scenario";
    private static final String COMMITTED_SCENARIO_PREFIX = "classpath:scenarios/";
    private static final Profiles DEV_PROFILE = Profiles.of("dev");

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
                    // Read before the wipe: a missing file, or one the parser refuses, then stops the boot
                    // with the data still there. The reseed's skip does not apply: the wipe removes its users.
                    List<ScenarioCommand> commands = readScenario(scenario);
                    repoCleaner.clearAll();
                    ensureMarketOpen();
                    devUserSeeder.ifAvailable(seeder -> seeder.run(null));
                    runScenario(scenario, commands);
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
            scenario = "classpath:scenarios/" + askScenario() + ".scenario";
        }
        executeMode(SeedProperties.Mode.valueOf(mode.toUpperCase(Locale.ROOT)), scenario);
    }

    /**
     * Asks which committed scenario to run. An answer that names none would name a missing file and stop
     * the boot, so it is refused, the valid names are shown, and the question is asked again. With no
     * console the default is taken, as for every question.
     *
     * @throws IllegalStateException when the console ends after an unknown answer, naming the valid choices
     */
    private String askScenario() {
        String answer = askChoice(SCENARIO_QUESTION, SCENARIOS, DEFAULT_SCENARIO);
        while (!SCENARIOS.contains(answer)) {
            System.out.println("  '" + answer + "' is not a scenario; choose one of " + SCENARIOS + ".");
            String unknown = answer;
            try {
                answer = promptForAnswer(SCENARIO_QUESTION, SCENARIOS, DEFAULT_SCENARIO)
                        .orElseThrow(() -> consoleEndedAfter(unknown, null));
            } catch (IOException e) {
                throw consoleEndedAfter(unknown, e);
            }
        }
        return answer;
    }

    private static IllegalStateException consoleEndedAfter(String unknownAnswer, IOException cause) {
        return new IllegalStateException("seed.mode=ask: '" + unknownAnswer + "' is not a scenario, and the"
                + " console ended before a valid one was chosen. Choose one of " + SCENARIOS + ".", cause);
    }

    /**
     * Prompts with a list of options and a default; returns the answer, or the default on empty input,
     * EOF or a read error.
     */
    private String askChoice(String label, List<String> options, String dflt) {
        try {
            return promptForAnswer(label, options, dflt).orElse(dflt);
        } catch (IOException e) {
            // No usable console is the no-terminal case: take the default, as for EOF.
            log.debug("seed.mode=ask: could not read the console; taking the default '{}'", dflt, e);
            return dflt;
        }
    }

    /**
     * Prompts once and reads one line.
     *
     * @return the trimmed, lowercased answer, the default for an empty line, or empty when the console has ended
     * @throws IOException when the console cannot be read
     */
    private Optional<String> promptForAnswer(String label, List<String> options, String dflt) throws IOException {
        System.out.println();
        System.out.println("  " + label + "? " + options + "  [default: " + dflt + "]");
        System.out.print("  > ");
        System.out.flush();
        String line = readConsoleLine();
        if (line == null) {
            return Optional.empty();
        }
        String value = line.trim().toLowerCase(Locale.ROOT);
        return Optional.of(value.isEmpty() ? dflt : value);
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

    // A committed scenario's accounts have passwords anyone can read in the repository. Under dev the
    // database is in memory; elsewhere it may be reachable, so say so. Refusing is #78's decision.
    private void warnIfCommittedScenarioOutsideDev(String scenario) {
        if (scenario.startsWith(COMMITTED_SCENARIO_PREFIX) && !environment.acceptsProfiles(DEV_PROFILE)) {
            log.warn("seed: scenario '{}' is a committed one, and the accounts it registers have passwords "
                    + "published in the repository; run it only against a database others cannot reach.", scenario);
        }
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
        warnIfCommittedScenarioOutsideDev(scenario);
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
