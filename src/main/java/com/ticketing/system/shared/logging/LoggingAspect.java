package com.ticketing.system.shared.logging;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

/**
 * Cross-cutting logger that traces every public application-service method.
 *
 * <p>For each invocation we emit:
 * <ul>
 *   <li>a DEBUG line on entry (class.method + argument count — no values, to avoid leaking PII/secrets)</li>
 *   <li>a DEBUG line on normal exit (with elapsed millis)</li>
 *   <li>a line on thrown exceptions (with elapsed millis + exception type/message): WARN from the
 *       outermost advised call on the thread, DEBUG from every advised call nested inside it</li>
 * </ul>
 *
 * <p>One service calls another through its proxy (e.g. {@code ReservationService} into
 * {@code InventoryService}), so a failure unwinds through several advised calls; logged at WARN by
 * each, it read as one failure per layer. The aspect therefore counts the advised calls on the
 * current thread and only the outermost one warns. A failure an outer service catches and handles
 * stays at DEBUG.
 *
 * <p>Argument values are intentionally <strong>not</strong> logged — lecture 2's rule
 * "never log secrets, tokens, passwords, or PII" applies. Method authors who need to log
 * specific values should do so explicitly via their own {@code @Slf4j} logger.
 */
@Aspect
@Component
@Slf4j
public class LoggingAspect {

    private static final int OUTERMOST = 0;
    private static final String FAILURE_FORMAT = "✗ {} ({} ms) — {}: {}";

    /** How many advised calls the current thread is inside, not counting the one being entered. */
    private final ThreadLocal<Integer> callDepth = ThreadLocal.withInitial(() -> OUTERMOST);

    /**
     * Matches every public method in a bounded context's {@code application.service} package
     * ({@code com.ticketing.system.<context>.application.service}). The legacy
     * {@code Core.Application.services} clause was dropped once that package was emptied (Step 8).
     */
    @Pointcut("execution(public * com.ticketing.system..application.service..*(..))")
    public void applicationService() {
    }

    @Around("applicationService()")
    public Object traceServiceMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        String signature = joinPoint.getSignature().toShortString();
        int argCount = joinPoint.getArgs() == null ? 0 : joinPoint.getArgs().length;
        long startNanos = System.nanoTime();

        log.debug("→ {} ({} args)", signature, argCount);
        int depth = callDepth.get();
        callDepth.set(depth + 1);
        try {
            Object result = joinPoint.proceed();
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            log.debug("← {} ({} ms)", signature, elapsedMs);
            return result;
        } catch (Throwable thrown) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            String failureType = thrown.getClass().getSimpleName();
            if (depth == OUTERMOST) {
                log.warn(FAILURE_FORMAT, signature, elapsedMs, failureType, thrown.getMessage());
            } else {
                log.debug(FAILURE_FORMAT, signature, elapsedMs, failureType, thrown.getMessage());
            }
            throw thrown;
        } finally {
            if (depth == OUTERMOST) {
                callDepth.remove();
            } else {
                callDepth.set(depth);
            }
        }
    }
}
