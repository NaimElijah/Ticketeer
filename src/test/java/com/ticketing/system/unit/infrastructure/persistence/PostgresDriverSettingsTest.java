package com.ticketing.system.unit.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.ticketing.system.shared.config.DatabaseProperties;
import com.ticketing.system.shared.persistence.PostgresDriverSettings;
import com.zaxxer.hikari.HikariDataSource;

// The pools are never started, so no database is needed.
class PostgresDriverSettingsTest {

    private static final String POSTGRES_URL = "jdbc:postgresql://db.example.com:5432/ticketing";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PostgresDriverSettings.class, Properties.class);

    @EnableConfigurationProperties(DatabaseProperties.class)
    static class Properties {
    }

    // The driver takes whole seconds. Rounding down would end a legitimate statement before the
    // configured time.
    @ParameterizedTest(name = "database.socket-timeout={0}")
    @CsvSource({"30s, 30", "1s, 1", "1500ms, 2"})
    void GivenAPostgresPool_WhenCreated_ThenItGetsTheSocketTimeoutInWholeSecondsRoundedUp(
            String configured, String seconds) {
        runner.withPropertyValues("database.socket-timeout=" + configured)
                .withBean(HikariDataSource.class, () -> pool(POSTGRES_URL))
                .run(context -> {
                    HikariDataSource pool = context.getBean(HikariDataSource.class);
                    assertThat(pool.getDataSourceProperties().getProperty("socketTimeout")).isEqualTo(seconds);
                    assertThat(pool.getDataSourceProperties().getProperty("tcpKeepAlive")).isEqualTo("true");
                });
    }

    // H2 refuses connection settings it does not know.
    @Test
    void GivenAnH2Pool_WhenCreated_ThenItGetsNoDriverSettings() {
        runner.withPropertyValues("database.socket-timeout=30s")
                .withBean(HikariDataSource.class, () -> pool("jdbc:h2:mem:settings"))
                .run(context -> assertThat(context.getBean(HikariDataSource.class).getDataSourceProperties()).isEmpty());
    }

    private static HikariDataSource pool(String url) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        return pool;
    }
}
