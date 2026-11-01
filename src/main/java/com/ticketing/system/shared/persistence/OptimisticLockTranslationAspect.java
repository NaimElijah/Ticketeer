package com.ticketing.system.shared.persistence;

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
 * <p>On the Jpa adapters, concurrency is enforced by {@code @Version} optimistic locking:
 * when two transactions write the same aggregate, the loser's commit fails and Spring raises an
 * {@link OptimisticLockingFailureException}. That is an infrastructure exception; left unhandled it
 * would leak past the application layer to the UI. This aspect re-types it into the domain's
 * designated concurrency exception so the conflict surfaces as a typed "please retry" failure. It is
 * a {@code DomainException}, so a presenter shows its message to the user through
 * {@code ServiceErrors.userMessage}; that message is therefore fixed text that names no class or
 * method, and the advised signature goes to the WARN log line instead.
 *
 * <p>Ordered {@link Ordered#HIGHEST_PRECEDENCE} so it wraps the {@code @Transactional} advice
 * (whose interceptor sits at {@link Ordered#LOWEST_PRECEDENCE}). The {@code @Version} conflict is
 * thrown at <em>commit</em> — as the transaction interceptor unwinds, after the service method body
 * has already returned — so only an outer aspect can observe and translate it.
 *
 * <p>This aspect <strong>only re-types</strong> the exception; it never re-invokes the method, so it
 * is safe for operations with non-DB side effects (e.g. notification dispatch). Two places retry, each on
 * Spring's {@code ConcurrencyFailureException} inside the service, before this aspect sees it:
 * {@code CheckoutService.runPhase3} runs a Phase 3 that lost a race again (ADR-015), and
 * {@code InventoryService} does the same for one event's stock return or hold release (ADR-017).
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class OptimisticLockTranslationAspect {

    private static final String CONFLICT_MESSAGE =
            "Someone else changed this at the same moment — please retry.";

    /** Every public application-service method — the same surface {@code LoggingAspect} traces:
     *  {@code com.ticketing.system.<context>.application.service}. The legacy Core.Application.services
     *  clause was dropped once that package was emptied (Step 8). */
    @Pointcut("execution(public * com.ticketing.system..application.service..*(..))")
    public void applicationService() {
    }

    @Around("applicationService()")
    public Object translateOptimisticLock(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            return joinPoint.proceed();
        } catch (OptimisticLockingFailureException conflict) {
            String signature = joinPoint.getSignature().toShortString();
            log.warn("Concurrent modification on {} — translating to a retryable conflict", signature);
            throw new ConcurrentReservationException(CONFLICT_MESSAGE, conflict);
        }
    }
}
