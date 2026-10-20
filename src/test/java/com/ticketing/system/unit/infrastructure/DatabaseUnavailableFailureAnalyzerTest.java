package com.ticketing.system.unit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.HibernateException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.io.support.SpringFactoriesLoader.ArgumentResolver;
import org.springframework.mock.env.MockEnvironment;

import com.ticketing.system.bootstrap.DatabaseUnavailableFailureAnalyzer;

/**
 * The startup report for an unreachable database (#28, ADR-005).
 *
 * <p>Two tests boot the real auto-configuration against a closed local port, so the analyzer is
 * checked against the failures actually raised: Hibernate's, when it is the first to connect, and
 * Flyway's, which connects first in the application (ADR-007). A Hibernate or Flyway upgrade that
 * changes its failure turns a test red instead of silently restoring a raw stack trace or the
 * misleading "set hibernate.dialect" report. Another test loads the analyzers the way Spring Boot
 * does, so a broken {@code META-INF/spring.factories} entry fails too.
 */
class DatabaseUnavailableFailureAnalyzerTest {

    // Port 1 (tcpmux) is closed on any machine that runs the suite, so the connection is refused at once.
    private static final String UNREACHABLE_URL = "jdbc:postgresql://127.0.0.1:1/ticketing";

    @Test
    void GivenUnreachableDatabase_WhenJpaStartupFails_ThenAnalysisNamesTheDatasourceAndHowToFixIt() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=" + UNREACHABLE_URL,
                        "spring.datasource.username=ticketing",
                        "spring.datasource.password=unused");

        runner.run(context -> {
            assertThat(context).hasFailed();

            FailureAnalysis analysis = analyzerFor(UNREACHABLE_URL).analyze(context.getStartupFailure());

            assertThat(analysis).isNotNull();
            assertThat(analysis.getDescription()).contains(UNREACHABLE_URL);
            assertThat(analysis.getAction()).contains("DB_HOST").contains("dev");
        });
    }

    // The application's own startup order: Flyway migrates before Hibernate starts (ADR-007), so an
    // unreachable database fails inside Flyway and Hibernate's dialect error never happens.
    @Test
    void GivenUnreachableDatabase_WhenFlywayRunsFirst_ThenAnalysisNamesTheDatasourceAndHowToFixIt() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        FlywayAutoConfiguration.class, HibernateJpaAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=" + UNREACHABLE_URL,
                        "spring.datasource.username=ticketing",
                        "spring.datasource.password=unused");

        runner.run(context -> {
            assertThat(context).hasFailed();

            FailureAnalysis analysis = analyzerFor(UNREACHABLE_URL).analyze(context.getStartupFailure());

            assertThat(analysis).isNotNull();
            assertThat(analysis.getDescription()).contains(UNREACHABLE_URL);
            assertThat(analysis.getAction()).contains("DB_HOST").contains("dev");
        });
    }

    @Test
    void GivenSqlFailureThatIsNotAConnectionFailure_WhenAnalyzed_ThenNoAnalysis() {
        SQLException missingTable = new SQLException("relation \"users\" does not exist", "42P01");

        FailureAnalysis analysis = analyzerFor(UNREACHABLE_URL)
                .analyze(new IllegalStateException("migration failed", missingTable));

        assertThat(analysis).isNull();
    }

    @Test
    void GivenSpringFactories_WhenBootLoadsFailureAnalyzers_ThenThisOneIsBuiltWithTheEnvironment() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.datasource.url", UNREACHABLE_URL);
        // The same two arguments Spring Boot's FailureAnalyzers offers. Boot's own analyzers that need
        // more than these fail to build here; they are recorded, not ours to assert on.
        ArgumentResolver bootArguments = ArgumentResolver.of(Environment.class, environment)
                .and(BeanFactory.class, new DefaultListableBeanFactory());
        Map<String, Throwable> buildFailures = new HashMap<>();

        List<FailureAnalyzer> analyzers = SpringFactoriesLoader.forDefaultResourceLocation().load(
                FailureAnalyzer.class, bootArguments,
                (factoryType, implementationName, failure) -> buildFailures.put(implementationName, failure));

        assertThat(buildFailures).doesNotContainKey(DatabaseUnavailableFailureAnalyzer.class.getName());
        FailureAnalyzer registered = analyzers.stream()
                .filter(DatabaseUnavailableFailureAnalyzer.class::isInstance)
                .findFirst()
                .orElseThrow(() -> new AssertionError("DatabaseUnavailableFailureAnalyzer is not in spring.factories"));
        FailureAnalysis analysis = registered.analyze(new HibernateException(
                "Unable to determine Dialect without JDBC metadata (please set 'hibernate.dialect')"));
        assertThat(analysis.getDescription()).contains(UNREACHABLE_URL);
    }

    @Test
    void GivenUrlWithQueryParameters_WhenAnalyzed_ThenTheQueryIsLeftOut() {
        String url = "jdbc:postgresql://db.example.com:5432/ticketing?sslmode=require&password=s3cret";

        FailureAnalysis analysis = analyzerFor(url).analyze(new HibernateException(
                "Unable to determine Dialect without JDBC metadata (please set 'hibernate.dialect')"));

        assertThat(analysis.getDescription())
                .contains("jdbc:postgresql://db.example.com:5432/ticketing")
                .doesNotContain("sslmode")
                .doesNotContain("s3cret");
    }

    @Test
    void GivenUnrelatedHibernateFailure_WhenAnalyzed_ThenNoAnalysis() {
        FailureAnalysis analysis = analyzerFor(UNREACHABLE_URL)
                .analyze(new HibernateException("Unknown entity: Foo"));

        assertThat(analysis).isNull();
    }

    private static DatabaseUnavailableFailureAnalyzer analyzerFor(String url) {
        return new DatabaseUnavailableFailureAnalyzer(
                new MockEnvironment().withProperty("spring.datasource.url", url));
    }
}
