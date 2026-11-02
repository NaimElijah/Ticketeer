package com.ticketing.system.unit.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.ticketing.system.bootstrap.scheduling.SessionAndOrderSweeper;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.domain.Session;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.shared.metrics.ISystemMetrics;
import com.ticketing.system.shared.metrics.MetricType;

/**
 * A swept-out guest session counts as a visitor exit (UC-46) only once the sweeper's tick commits: a
 * tick that rolls back deleted nothing, and the next tick sweeps the same session again (#187).
 *
 * <p>The sweeper is a bean in a small Spring context with transaction management on and a real
 * {@link DataSourceTransactionManager} over an embedded H2 database, so {@code sweep()} runs through
 * the real {@code @Transactional} proxy and its commit or rollback. The repositories are test doubles
 * of the project's own ports.
 */
class SessionAndOrderSweeperTransactionTest {

    // ---- Defect 7: the visitor-exit metric is recorded after commit (#187) ----

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final String GUEST_SESSION_ID = "guest-sid";

    private final SessionRepository sessions = mock(SessionRepository.class);
    private final ActiveOrderRepository orders = mock(ActiveOrderRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final ISystemMetrics metrics = mock(ISystemMetrics.class);

    @BeforeEach
    void anExpiredGuestSessionWithoutACart() {
        Session expiredGuest = new Session(GUEST_SESSION_ID, null, T0.minusSeconds(7200), T0.minusSeconds(60));
        when(sessions.findExpiredBefore(T0)).thenReturn(List.of(expiredGuest));
        when(orders.getBySessionId(any())).thenReturn(Optional.empty());
    }

    @Test
    void GivenTickThatRollsBack_WhenSweep_ThenNoVisitorExitIsRecorded() {
        when(orders.findExpired(any())).thenThrow(new OptimisticLockingFailureException("concurrent cart update"));

        contextWithTheSweeper().run(context -> {
            assertThat(context).hasNotFailed();
            SessionAndOrderSweeper sweeper = context.getBean(SessionAndOrderSweeper.class);

            assertThrows(OptimisticLockingFailureException.class, sweeper::sweep, "precondition: the tick fails and rolls back");

            verify(metrics, never()).record(MetricType.VISITOR_EXIT);
        });
    }

    @Test
    void GivenTickThatCommits_WhenSweep_ThenTheVisitorExitIsRecordedOnce() {
        when(orders.findExpired(any())).thenReturn(List.of());

        contextWithTheSweeper().run(context -> {
            assertThat(context).hasNotFailed();

            context.getBean(SessionAndOrderSweeper.class).sweep();

            verify(metrics, times(1)).record(MetricType.VISITOR_EXIT);
        });
    }

    private ApplicationContextRunner contextWithTheSweeper() {
        return new ApplicationContextRunner()
                .withUserConfiguration(Transactions.class)
                .withBean(SessionRepository.class, () -> sessions)
                .withBean(ActiveOrderRepository.class, () -> orders)
                .withBean(EventRepository.class, () -> events)
                .withBean(ISystemMetrics.class, () -> metrics)
                .withBean(Clock.class, () -> Clock.fixed(T0, ZoneOffset.UTC))
                .withBean(SessionAndOrderSweeper.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions {

        @Bean(destroyMethod = "shutdown")
        EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
