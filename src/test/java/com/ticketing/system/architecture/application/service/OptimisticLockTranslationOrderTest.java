package com.ticketing.system.architecture.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import com.ticketing.system.shared.exception.ConcurrentReservationException;
import com.ticketing.system.shared.persistence.OptimisticLockTranslationAspect;

/**
 * {@link OptimisticLockTranslationAspect} must wrap the {@code @Transactional} advice: a {@code @Version}
 * conflict surfaces when the transaction commits, after the service method has returned, so only an
 * aspect outside the transaction interceptor sees it. {@code OptimisticLockTranslationAspectTest} proves
 * the translation on a plain proxy; this test proves the order, in a Spring context that proxies a
 * {@code @Transactional} service the way the application does, with Spring's default order for the
 * transaction advice.
 *
 * <p>The stand-in service sits in an {@code ..application.service} package so the aspect's pointcut
 * matches it. Its transaction manager fails every commit with the conflict, as Hibernate's flush of a
 * stale row does.
 */
class OptimisticLockTranslationOrderTest {

    /** Fails each commit with the same conflict; declares no public method, so the pointcut skips it. */
    static final class ConflictAtCommit extends AbstractPlatformTransactionManager {
        private final OptimisticLockingFailureException conflict =
                new OptimisticLockingFailureException("row was updated by another transaction");

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            throw conflict;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    /** A stand-in application service whose only write is its transaction's commit. */
    static class TransactionalService {
        @Transactional
        public String write() {
            return "written";
        }
    }

    @Configuration
    @EnableTransactionManagement
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class ProxiesLikeTheApplication {
    }

    @Test
    void GivenAConflictRaisedAtCommit_WhenTheTransactionalServiceReturns_ThenTheCallerGetsAConcurrentReservation() {
        ConflictAtCommit transactions = new ConflictAtCommit();

        new ApplicationContextRunner()
                .withUserConfiguration(ProxiesLikeTheApplication.class)
                .withBean(PlatformTransactionManager.class, () -> transactions)
                .withBean(OptimisticLockTranslationAspect.class)
                .withBean(TransactionalService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(AopUtils.isAopProxy(context.getBean(PlatformTransactionManager.class)))
                            .as("precondition: the aspect does not advise the transaction manager itself")
                            .isFalse();
                    TransactionalService service = context.getBean(TransactionalService.class);

                    assertThatThrownBy(service::write)
                            .as("the conflict thrown as the transaction commits must reach the caller re-typed")
                            .isInstanceOf(ConcurrentReservationException.class)
                            .hasCause(transactions.conflict);
                });
    }
}
