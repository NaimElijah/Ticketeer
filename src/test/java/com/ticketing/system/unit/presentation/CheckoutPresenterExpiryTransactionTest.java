package com.ticketing.system.unit.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ticketing.system.sales.application.event.OrderExpiredEvent;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.ui.presenters.order.CheckoutPresenter;
import com.ticketing.system.ui.session.SessionIdentity;

import ch.qos.logback.classic.Level;

/**
 * {@link CheckoutPresenter} tells an open checkout view that its order expired only once the sweep
 * that expired it has committed, and a view that fails to take the news never undoes the sweep.
 *
 * <p>The presenter is a bean in a small Spring context with transaction management on and a real
 * {@link DataSourceTransactionManager} over an embedded H2 database. {@link ExpirySweep} stands in for
 * the order sweeper: it publishes {@link OrderExpiredEvent} inside its own {@code @Transactional}
 * method and records how its transaction completed. The presenter's collaborators are test doubles of
 * the project's own services; they take no part in the expiry push.
 */
class CheckoutPresenterExpiryTransactionTest {

    // ---- UI-B9: the expiry push runs after the sweep commits (#187) ----

    private static final int BUYER_ID = 42;

    @Test
    void GivenASweepThatRollsBack_WhenItExpiresTheOrder_ThenTheViewIsNotTold() {
        contextWithThePresenterAndASweep().run(context -> {
            assertThat(context).hasNotFailed();
            AtomicInteger told = new AtomicInteger();
            context.getBean(CheckoutPresenter.class).registerExpiryListener(buyersView(told::incrementAndGet));
            ExpirySweep sweep = context.getBean(ExpirySweep.class);

            assertThrows(IllegalStateException.class, sweep::expireThenFail, "precondition: the sweep fails");

            assertEquals(List.of(TransactionSynchronization.STATUS_ROLLED_BACK), sweep.completions(),
                    "precondition: the sweep's transaction rolled back");
            assertEquals(0, told.get(), "a sweep that rolled back expired nothing, so the view must not be told");
        });
    }

    @Test
    void GivenAViewThatThrowsWhenTold_WhenASweepExpiresTheOrder_ThenTheSweepStillCommits() {
        contextWithThePresenterAndASweep().run(context -> {
            assertThat(context).hasNotFailed();
            AtomicInteger told = new AtomicInteger();
            context.getBean(CheckoutPresenter.class).registerExpiryListener(buyersView(() -> {
                told.incrementAndGet();
                throw new IllegalStateException("the view's UI is gone");
            }));
            ExpirySweep sweep = context.getBean(ExpirySweep.class);

            assertDoesNotThrow(sweep::expire, "a view's failure must not reach the sweep");

            assertEquals(List.of(TransactionSynchronization.STATUS_COMMITTED), sweep.completions(),
                    "a view's failure must not roll the sweep back");
            assertEquals(1, told.get(), "the view must still have been told once");
        });
    }

    // Two views that both throw: whichever is told first, the other is still told, and each failure is
    // logged with its exception rather than swallowed.
    @Test
    void GivenTwoViewsThatThrowWhenTold_WhenASweepExpiresTheOrder_ThenBothAreToldAndEachFailureIsLogged() {
        contextWithThePresenterAndASweep().run(context -> {
            assertThat(context).hasNotFailed();
            AtomicInteger told = new AtomicInteger();
            CheckoutPresenter presenter = context.getBean(CheckoutPresenter.class);
            for (int view = 0; view < 2; view++) {
                presenter.registerExpiryListener(buyersView(() -> {
                    told.incrementAndGet();
                    throw new IllegalStateException("the view's UI is gone");
                }));
            }

            try (LogCapture log = LogCapture.of(CheckoutPresenter.class)) {
                context.getBean(ExpirySweep.class).expire();

                assertEquals(2, told.get(), "one view's failure must not stop the other from being told");
                assertEquals(List.of(IllegalStateException.class.getName(), IllegalStateException.class.getName()),
                        log.throwableClassNamesAt(Level.WARN), "each view's failure must be logged with its exception");
            }
        });
    }

    @Test
    void GivenASweepThatCommits_WhenItExpiresTheOrder_ThenTheViewIsToldOnce() {
        contextWithThePresenterAndASweep().run(context -> {
            assertThat(context).hasNotFailed();
            AtomicInteger told = new AtomicInteger();
            context.getBean(CheckoutPresenter.class).registerExpiryListener(buyersView(told::incrementAndGet));
            ExpirySweep sweep = context.getBean(ExpirySweep.class);

            sweep.expire();

            assertEquals(List.of(TransactionSynchronization.STATUS_COMMITTED), sweep.completions());
            assertEquals(1, told.get(), "a committed expiry must reach the view once");
        });
    }

    // fallbackExecution: an expiry published outside any transaction still reaches the view.
    @Test
    void GivenAnExpiryPublishedOutsideATransaction_WhenPublished_ThenTheViewIsToldOnce() {
        contextWithThePresenterAndASweep().run(context -> {
            assertThat(context).hasNotFailed();
            AtomicInteger told = new AtomicInteger();
            context.getBean(CheckoutPresenter.class).registerExpiryListener(buyersView(told::incrementAndGet));

            context.publishEvent(new OrderExpiredEvent(BUYER_ID, null));

            assertEquals(1, told.get(), "an expiry published without a transaction must still reach the view");
        });
    }

    private static CheckoutPresenter.ExpiryListener buyersView(Runnable onExpired) {
        return new CheckoutPresenter.ExpiryListener() {
            @Override
            public boolean matches(int userId, String sessionId) {
                return userId == BUYER_ID;
            }

            @Override
            public void onExpired() {
                onExpired.run();
            }
        };
    }

    private static ApplicationContextRunner contextWithThePresenterAndASweep() {
        return new ApplicationContextRunner()
                .withUserConfiguration(Transactions.class)
                .withBean(ReservationService.class, () -> mock(ReservationService.class))
                .withBean(CheckoutService.class, () -> mock(CheckoutService.class))
                .withBean(SessionIdentity.class, () -> mock(SessionIdentity.class))
                .withBean(CheckoutPresenter.class)
                .withBean(ExpirySweep.class);
    }

    /** Stands in for the order sweeper: expires the buyer's order inside its own transaction. */
    static class ExpirySweep {

        private final ApplicationEventPublisher publisher;
        private final List<Integer> completions = new CopyOnWriteArrayList<>();

        ExpirySweep(ApplicationEventPublisher publisher) {
            this.publisher = publisher;
        }

        @Transactional
        public void expire() {
            recordHowTheTransactionCompletes();
            publisher.publishEvent(new OrderExpiredEvent(BUYER_ID, null));
        }

        @Transactional
        public void expireThenFail() {
            expire();
            throw new IllegalStateException("the sweep failed after expiring the order");
        }

        public List<Integer> completions() {
            return List.copyOf(completions);
        }

        private void recordHowTheTransactionCompletes() {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    completions.add(status);
                }
            });
        }
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
