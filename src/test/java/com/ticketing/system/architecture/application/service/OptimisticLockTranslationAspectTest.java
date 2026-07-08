package com.ticketing.system.architecture.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.dao.OptimisticLockingFailureException;

import com.ticketing.system.shared.exception.ConcurrentReservationException;
import com.ticketing.system.Infrastructure.persistence.OptimisticLockTranslationAspect;

/**
 * Unit test for {@link OptimisticLockTranslationAspect}.
 *
 * <p>The aspect advises {@code execution(public * com.ticketing.system..application.service..*(..))},
 * so the stand-in service it wraps must live in an {@code ..application.service} package — hence this
 * test sits in {@code architecture.application.service} rather than under the usual {@code unit/} tree.
 * {@link AspectJProxyFactory} weaves the aspect around a plain target with no Spring context, keeping
 * the test fast and focused purely on the advice's behaviour. This test also guards against silent
 * pointcut breakage (a mismatched pointcut would let the raw {@code OptimisticLockingFailureException}
 * escape untranslated).
 */
class OptimisticLockTranslationAspectTest {

    /** Minimal stand-in for a real application service — same package, so the pointcut advises it. */
    static class FakeService {
        Object result = "ok";
        RuntimeException toThrow;

        public Object run() {
            if (toThrow != null) {
                throw toThrow;
            }
            return result;
        }
    }

    private static FakeService advised(FakeService target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new OptimisticLockTranslationAspect());
        return factory.getProxy();
    }

    @Test
    void translatesOptimisticLockFailureIntoConcurrentReservationException() {
        FakeService target = new FakeService();
        OptimisticLockingFailureException conflict = new OptimisticLockingFailureException("version mismatch");
        target.toThrow = conflict;

        FakeService proxy = advised(target);

        ConcurrentReservationException thrown =
                assertThrows(ConcurrentReservationException.class, proxy::run);
        assertSame(conflict, thrown.getCause(), "the original failure must be preserved as the cause");
    }

    @Test
    void passesSuccessfulResultsThroughUnchanged() {
        FakeService target = new FakeService();
        target.result = "done";

        FakeService proxy = advised(target);

        assertEquals("done", proxy.run());
    }

    @Test
    void leavesNonConflictExceptionsUntouched() {
        FakeService target = new FakeService();
        IllegalStateException unrelated = new IllegalStateException("not a conflict");
        target.toThrow = unrelated;

        FakeService proxy = advised(target);

        assertSame(unrelated, assertThrows(IllegalStateException.class, proxy::run));
    }
}
