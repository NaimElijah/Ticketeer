package com.ticketing.system.unit.infrastructure.seed.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.bootstrap.config.SeedProperties;
import com.ticketing.system.bootstrap.dev.seed.scenario.ScenarioRunner;
import com.ticketing.system.testutil.LogCapture;

/**
 * The seeder names the database it works on without the URL's query string, which can carry
 * credentials, as {@code DatabaseUnavailableFailureAnalyzer} does (#187): in the WARNs of a refused or
 * an allowed wipe, and in the {@code ask} menu's banner.
 */
class ScenarioRunnerDatasourceUrlLogTest {

    // ---- Defect 6: the datasource URL is shown without its query string (#187) ----

    private static final String URL_WITHOUT_QUERY = "jdbc:postgresql://db.example.com:5432/ticketing";
    private static final String DB_USER = "seed_operator";
    private static final String DB_PASSWORD = "hunter2-db-password";
    private static final String URL_WITH_CREDENTIALS =
            URL_WITHOUT_QUERY + "?user=" + DB_USER + "&password=" + DB_PASSWORD + "&sslmode=require";
    private static final String SCENARIO = "classpath:scenarios/demo.scenario";
    private static final String ANSWER_OFF = "off\n";

    private final ScenarioRunnerFixture fixture = new ScenarioRunnerFixture();
    private final InputStream originalIn = System.in;
    private final PrintStream originalOut = System.out;
    private LogCapture logs;

    @BeforeEach
    void configureTheUrl() {
        fixture.datasourceUrl = URL_WITH_CREDENTIALS;
        logs = LogCapture.of(ScenarioRunner.class);
    }

    @AfterEach
    void restore() {
        logs.close();
        System.setIn(originalIn);
        System.setOut(originalOut);
    }

    @Test
    void GivenWipeWithoutTheOptIn_WhenRefused_ThenTheDatabaseIsNamedWithoutItsQuery() {
        fixture.assumeYes = false;

        fixture.runner(SeedProperties.Mode.WIPE, true, SCENARIO).run(null);

        assertFalse(logs.eventsContaining(URL_WITHOUT_QUERY).isEmpty(), "precondition: the refusal names the database");
        assertNoQueryLogged();
    }

    @Test
    void GivenWipeWithTheOptIn_WhenItWipes_ThenTheDatabaseIsNamedWithoutItsQuery() {
        fixture.assumeYes = true;

        fixture.runner(SeedProperties.Mode.WIPE, true, SCENARIO).run(null);

        assertFalse(logs.eventsContaining(URL_WITHOUT_QUERY).isEmpty(), "precondition: the wipe names the database");
        assertNoQueryLogged();
    }

    @Test
    void GivenAskMode_WhenTheMenuShowsTheDatabase_ThenItsQueryIsLeftOut() {
        System.setIn(new ByteArrayInputStream(ANSWER_OFF.getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        System.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));

        fixture.runner(SeedProperties.Mode.ASK, true, SCENARIO).run(null);

        String shown = console.toString(StandardCharsets.UTF_8);
        assertTrue(shown.contains(URL_WITHOUT_QUERY), "precondition: the menu names the database, but showed: " + shown);
        assertFalse(shown.contains(DB_PASSWORD), "the menu must not show the URL's password: " + shown);
        assertFalse(shown.contains(DB_USER), "the menu must not show the URL's query: " + shown);
    }

    private void assertNoQueryLogged() {
        assertEquals(List.of(), logs.eventsContaining(DB_PASSWORD), "the URL's password must not be logged");
        assertEquals(List.of(), logs.eventsContaining(DB_USER), "the URL's query must not be logged");
    }
}
