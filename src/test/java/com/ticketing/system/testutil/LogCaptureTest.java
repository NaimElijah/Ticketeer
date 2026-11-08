package com.ticketing.system.testutil;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.spi.FilterReply;

/**
 * {@link LogCapture#assertNeverLogged} proves an absence only when the path logged something, and finds a
 * value wherever an event carries it, a suppressed throwable included (#189). A read made while another
 * thread is appending waits for that append, so it never copies the list half-way through one.
 */
class LogCaptureTest {

    private static final Logger LOG = LoggerFactory.getLogger(LogCaptureTest.class);
    private static final String SECRET = "dana.levi@example.com";

    private LogCapture logs;

    @BeforeEach
    void attach() {
        logs = LogCapture.of(LogCaptureTest.class);
    }

    @AfterEach
    void detach() {
        logs.close();
    }

    private static final long AWAIT_SECONDS = 10;
    private static final String HELD_EVENT = "appended while the test thread reads";

    // Logback appends inside AppenderBase.doAppend, which holds the appender's monitor, to a plain ArrayList.
    // A filter on the capture's appender runs inside that monitor, so it can hold an append open while the
    // test thread reads: a read under the same monitor must wait, and then see the finished append. An
    // unguarded read returns at once, from a list the other thread is still changing.
    @Test
    void GivenAnAppendInProgressOnAnotherThread_WhenTheTestThreadReads_ThenTheReadWaitsForItAndSeesTheEvent()
            throws InterruptedException {
        CountDownLatch appendStarted = new CountDownLatch(1);
        CountDownLatch finishAppend = new CountDownLatch(1);
        captureAppender().addFilter(new Filter<>() {
            @Override
            public FilterReply decide(ILoggingEvent event) {
                appendStarted.countDown();
                awaitQuietly(finishAppend);
                return FilterReply.NEUTRAL;
            }
        });
        AtomicReference<List<String>> read = new AtomicReference<>();
        Thread writer = new Thread(() -> LOG.info(HELD_EVENT), "log-writer");
        Thread reader = new Thread(() -> read.set(logs.messagesAt(Level.INFO)), "log-reader");
        try {
            writer.start();
            assertTrue(appendStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS), "precondition: the append has begun");
            reader.start();
            awaitBlockedOnOrDone(reader, captureAppender());

            assertNull(read.get(), "the read must wait for the append in progress, but returned " + read.get());
            finishAppend.countDown();
            reader.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
            assertEquals(List.of(HELD_EVENT), read.get(), "once the append ends, the read sees it");
        } finally {
            finishAppend.countDown();
            writer.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
            reader.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
        }
    }

    // The ListAppender LogCapture attached to this class's logger: the only one there.
    private static ListAppender<ILoggingEvent> captureAppender() {
        Iterator<Appender<ILoggingEvent>> attached = ((ch.qos.logback.classic.Logger) LOG).iteratorForAppenders();
        while (attached.hasNext()) {
            if (attached.next() instanceof ListAppender<ILoggingEvent> capture) {
                return capture;
            }
        }
        throw new AssertionError("LogCapture attached no ListAppender to " + LOG.getName());
    }

    // Waits, bounded, until the reader is blocked on the appender's monitor or has finished; either answers
    // the test. Blocked on another monitor, a class being initialised say, it answers nothing, so the wait
    // goes on.
    private static void awaitBlockedOnOrDone(Thread reader, Object monitor) {
        // ThreadInfo names a monitor by its class and its identity hash in hex.
        String monitorName = monitor.getClass().getName() + '@'
                + Integer.toHexString(System.identityHashCode(monitor));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (reader.getState() != Thread.State.TERMINATED && !blockedOn(reader, monitorName)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the reader neither blocked on the appender nor finished; state "
                        + reader.getState());
            }
            Thread.onSpinWait();
        }
    }

    private static boolean blockedOn(Thread thread, String monitorName) {
        ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId());
        return info != null && info.getThreadState() == Thread.State.BLOCKED
                && monitorName.equals(info.getLockName());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void GivenNothingLogged_WhenAssertingAValueIsNeverLogged_ThenTheAssertionFails() {
        AssertionError refused = assertThrows(AssertionError.class, () -> logs.assertNeverLogged(SECRET),
            "with nothing recorded the absence proves nothing, so the assertion must not pass");

        assertTrue(refused.getMessage().contains("nothing was logged"), "the failure must say why: " + refused.getMessage());
    }

    @Test
    void GivenEventsThatDoNotCarryTheValue_WhenAssertingItIsNeverLogged_ThenTheAssertionPasses() {
        LOG.warn("search failed, query length={}", SECRET.length());

        assertDoesNotThrow(() -> logs.assertNeverLogged(SECRET));
    }

    @Test
    void GivenAnEventWhoseMessageCarriesTheValue_WhenAssertingItIsNeverLogged_ThenTheAssertionFails() {
        LOG.debug("search failed for {}", SECRET.toUpperCase(Locale.ROOT));

        assertThrows(AssertionError.class, () -> logs.assertNeverLogged(SECRET),
            "the value must be found in the message, whatever its case");
    }

    @Test
    void GivenTheValueOnlyInACause_WhenAssertingItIsNeverLogged_ThenTheAssertionFails() {
        LOG.error("search failed", new IllegalStateException("wrapped", new IllegalArgumentException(SECRET)));

        assertThrows(AssertionError.class, () -> logs.assertNeverLogged(SECRET));
    }

    @Test
    void GivenTheValueOnlyInASuppressedException_WhenAssertingItIsNeverLogged_ThenTheAssertionFails() {
        IllegalStateException failure = new IllegalStateException("search failed");
        failure.addSuppressed(new IllegalArgumentException("close failed for " + SECRET));
        LOG.error("search failed", failure);

        assertThrows(AssertionError.class, () -> logs.assertNeverLogged(SECRET),
            "a value in a suppressed exception reaches the log as well");
    }

    @Test
    void GivenTheValueInTheCauseOfASuppressedException_WhenAssertingItIsNeverLogged_ThenTheAssertionFails() {
        IllegalStateException failure = new IllegalStateException("search failed");
        failure.addSuppressed(new IllegalStateException("retry failed", new IllegalArgumentException(SECRET)));
        LOG.error("search failed", failure);

        assertThrows(AssertionError.class, () -> logs.assertNeverLogged(SECRET));
    }
}
