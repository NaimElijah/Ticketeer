package com.ticketing.system.architecture.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import com.ticketing.system.shared.logging.LoggingAspect;
import com.ticketing.system.testutil.LogCapture;

import ch.qos.logback.classic.Level;

/**
 * #187: {@link LoggingAspect} advises every public application-service method, and one service calls
 * another through its Spring proxy (e.g. {@code ReservationService} into {@code InventoryService}).
 * One failure must log one WARN, from the outermost advised call it unwinds through; the advised
 * calls it passes on the way out log at DEBUG.
 *
 * <p>The stand-in services must sit in an {@code ..application.service} package for the pointcut to
 * match them, as in {@link OptimisticLockTranslationAspectTest}. One aspect instance advises both,
 * as the single Spring bean does.
 */
class LoggingAspectTest {

    private static final String FAILURE = "boom";
    private static final String RECOVERED = "recovered";

    /** The service called second, through its proxy. */
    static class InnerService {
        public void fail() {
            throw new IllegalStateException(FAILURE);
        }

        public String succeed() {
            return "ok";
        }
    }

    /** The service called first; it reaches {@link InnerService} through the inner proxy. */
    static class OuterService {
        private final InnerService inner;

        OuterService(InnerService inner) {
            this.inner = inner;
        }

        public void callFailingInner() {
            inner.fail();
        }

        public void callSucceedingInnerThenFail() {
            inner.succeed();
            throw new IllegalStateException(FAILURE);
        }

        public String callFailingInnerAndRecover() {
            try {
                inner.fail();
            } catch (IllegalStateException handled) {
                return RECOVERED;
            }
            throw new AssertionError("the inner service was expected to fail");
        }
    }

    private LogCapture logs;
    private InnerService inner;
    private OuterService outer;

    @BeforeEach
    void adviseBothServicesWithOneAspect() {
        LoggingAspect aspect = new LoggingAspect();
        inner = advised(new InnerService(), aspect);
        outer = advised(new OuterService(inner), aspect);
        logs = LogCapture.of(LoggingAspect.class);
    }

    @AfterEach
    void detachLogCapture() {
        logs.close();
    }

    @Test
    void GivenInnerFailurePropagatedThroughOuter_WhenBothAdvised_ThenOneWarnFromTheOuterCall() {
        assertThrows(IllegalStateException.class, outer::callFailingInner);

        List<String> warnings = logs.messagesAt(Level.WARN);
        assertEquals(1, warnings.size(), "one failure must log one WARN, got: " + warnings);
        assertTrue(warnings.get(0).contains(OuterService.class.getSimpleName()),
                "the WARN must come from the outermost call: " + warnings.get(0));
    }

    @Test
    void GivenInnerFailurePropagatedThroughOuter_WhenBothAdvised_ThenTheInnerCallLogsItAtDebug() {
        assertThrows(IllegalStateException.class, outer::callFailingInner);

        List<String> debugLines = logs.messagesAt(Level.DEBUG);
        assertTrue(debugLines.stream().anyMatch(line -> line.contains(InnerService.class.getSimpleName())
                        && line.contains(IllegalStateException.class.getSimpleName())),
                "the inner call's failure must be logged at DEBUG, got: " + debugLines);
    }

    @Test
    void GivenNestedFailureAlreadyUnwound_WhenATopLevelCallFails_ThenItLogsWarn() {
        assertThrows(IllegalStateException.class, outer::callFailingInner);
        int warningsBefore = logs.messagesAt(Level.WARN).size();

        assertThrows(IllegalStateException.class, inner::fail);

        List<String> warnings = logs.messagesAt(Level.WARN);
        assertEquals(warningsBefore + 1, warnings.size(),
                "a top-level failure after a nested one must log its own WARN, got: " + warnings);
        assertTrue(warnings.get(warnings.size() - 1).contains(InnerService.class.getSimpleName()),
                "the new WARN must come from the top-level call: " + warnings);
    }

    @Test
    void GivenTopLevelCallSucceeded_WhenALaterTopLevelCallFails_ThenItLogsOneWarn() {
        assertEquals(RECOVERED, outer.callFailingInnerAndRecover());
        int warningsBefore = logs.messagesAt(Level.WARN).size();

        assertThrows(IllegalStateException.class, inner::fail);

        List<String> warnings = logs.messagesAt(Level.WARN);
        assertEquals(warningsBefore + 1, warnings.size(),
                "a top-level failure after a successful top-level call must log one WARN, got: " + warnings);
        assertTrue(warnings.get(warnings.size() - 1).contains(InnerService.class.getSimpleName()),
                "the new WARN must come from the failing top-level call: " + warnings);
    }

    @Test
    void GivenInnerCallSucceeded_WhenTheOuterCallThenFails_ThenOneWarnFromTheOuterCall() {
        assertThrows(IllegalStateException.class, outer::callSucceedingInnerThenFail);

        List<String> warnings = logs.messagesAt(Level.WARN);
        assertEquals(1, warnings.size(), "the outer failure must log one WARN, got: " + warnings);
        assertTrue(warnings.get(0).contains(OuterService.class.getSimpleName()),
                "the WARN must come from the outer call: " + warnings.get(0));
    }

    @Test
    void GivenOuterHandlesTheInnerFailure_WhenBothAdvised_ThenNoWarnIsLogged() {
        assertEquals(RECOVERED, outer.callFailingInnerAndRecover());

        List<String> warnings = logs.messagesAt(Level.WARN);
        assertEquals(List.of(), warnings, "a failure the outer service handled must not log a WARN");
    }

    @Test
    void GivenOuterHandlesTheInnerFailure_WhenBothAdvised_ThenTheInnerFailureIsLoggedAtDebug() {
        assertEquals(RECOVERED, outer.callFailingInnerAndRecover());

        List<String> debugLines = logs.messagesAt(Level.DEBUG);
        assertTrue(debugLines.stream().anyMatch(line -> line.contains(InnerService.class.getSimpleName())
                        && line.contains(IllegalStateException.class.getSimpleName())),
                "the handled inner failure must still be logged at DEBUG, got: " + debugLines);
    }

    private static <T> T advised(T target, LoggingAspect aspect) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }
}
