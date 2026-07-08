package com.ticketing.system.Infrastructure.persistence;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import com.ticketing.system.shared.exception.ConcurrentReservationException;

import lombok.extern.slf4j.Slf4j;

/**
 * Translates a JPA optimistic-lock conflict into the domain's {@link ConcurrentReservationException}.
 *
 * <p>Under the {@code jpa} profile, concurrency is enforced by {@code @Version} optimistic locking:
 * when two transactions write the same aggregate, the loser's commit fails and Spring raises an
 * {@link OptimisticLockingFailureException}. That is an infrastructure exception; left unhandled it
 * would leak past the application layer to the UI. This aspect re-types it into the domain's
 * designated concurrency exception so the conflict surfaces as a typed "please retry" failure that
 * presenters already map to a clean outcome (their {@code catch (RuntimeException)} boundary).
 *
 * <p>Ordered {@link Ordered#HIGHEST_PRECEDENCE} so it wraps the {@code @Transactional} advice
 * (whose interceptor sits at {@link Ordered#LOWEST_PRECEDENCE}). The {@code @Version} conflict is
 * thrown at <em>commit</em> — as the transaction interceptor unwinds, after the service method body
 * has already returned — so only an outer aspect can observe and translate it.
 *
 * <p>This aspect <strong>only re-types</strong> the exception; it never re-invokes the method, so it
 * is safe for operations with non-DB side effects (e.g. notification dispatch). Bounded auto-retry of
 * the safe pure-DB paths, and the concurrency proof that exercises it, are tracked separately
 * (V3-TX-03).
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class OptimisticLockTranslationAspect {

    /** Every public application-service method — the same surface {@code LoggingAspect} traces.
     *  Covers both the canonical per-context service package (<context>.application.service) and the
     *  not-yet-migrated legacy Core.Application.services; the legacy clause is removed at Step 10. */
    @Pointcut("execution(public * com.ticketing.system..application.service..*(..)) "
            + "|| execution(public * com.ticketing.system.Core.Application.services..*(..))")
    public void applicationService() {
    }

    @Around("applicationService()")
    public Object translateOptimisticLock(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            return joinPoint.proceed();
        } catch (OptimisticLockingFailureException conflict) {
            String signature = joinPoint.getSignature().toShortString();
            log.warn("Concurrent modification on {} — translating to a retryable conflict", signature);
            throw new ConcurrentReservationException(
                    "Concurrent modification detected while running " + signature + " — please retry",
                    conflict);
        }
    }
}
