package com.ticketing.system.Infrastructure.logging;

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
 *   <li>a WARN line on thrown exceptions (with elapsed millis + exception type/message)</li>
 * </ul>
 *
 * <p>Argument values are intentionally <strong>not</strong> logged — lecture 2's rule
 * "never log secrets, tokens, passwords, or PII" applies. Method authors who need to log
 * specific values should do so explicitly via their own {@code @Slf4j} logger.
 */
@Aspect
@Component
@Slf4j
public class LoggingAspect {

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
        try {
            Object result = joinPoint.proceed();
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            log.debug("← {} ({} ms)", signature, elapsedMs);
            return result;
        } catch (Throwable thrown) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            log.warn("✗ {} ({} ms) — {}: {}",
                    signature, elapsedMs,
                    thrown.getClass().getSimpleName(),
                    thrown.getMessage());
            throw thrown;
        }
    }
}
