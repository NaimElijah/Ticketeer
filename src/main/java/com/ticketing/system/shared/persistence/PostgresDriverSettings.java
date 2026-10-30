package com.ticketing.system.shared.persistence;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ticketing.system.shared.config.DatabaseProperties;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Bounds PostgreSQL connections that hang (SLR.6, #173). Hikari's connection timeout bounds only
 * borrowing a connection; a query already running on a lent one waits until the operating system gives
 * up on its TCP connection, which takes minutes, and enough such reads exhaust the pool. The PostgreSQL
 * driver's {@code socketTimeout} ends such a read, and {@code tcpKeepAlive} lets the operating system
 * notice a dead peer on an idle connection.
 *
 * <p>Set on the pool as driver properties rather than in the URL, so they also reach a datasource whose
 * URL comes from elsewhere, such as a Testcontainers service connection. Only a PostgreSQL URL gets
 * them: H2, which dev and the test suites run on, refuses settings it does not know.
 */
@Configuration(proxyBeanMethods = false)
public class PostgresDriverSettings {

    private static final String POSTGRES_URL_PREFIX = "jdbc:postgresql:";

    // Static, as a BeanPostProcessor should be. The provider defers creating the properties record until
    // the datasource exists, so the record is still bound and validated like any other.
    @Bean
    static BeanPostProcessor postgresDriverTimeouts(ObjectProvider<DatabaseProperties> properties) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource pool && pool.getJdbcUrl() != null
                        && pool.getJdbcUrl().startsWith(POSTGRES_URL_PREFIX)) {
                    pool.addDataSourceProperty("socketTimeout",
                            String.valueOf(properties.getObject().socketTimeout().toSeconds()));
                    pool.addDataSourceProperty("tcpKeepAlive", "true");
                }
                return bean;
            }
        };
    }
}
