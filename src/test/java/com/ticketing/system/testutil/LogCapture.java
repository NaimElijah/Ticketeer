package com.ticketing.system.testutil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;

/**
 * Test helper: records every event one class's Logback logger emits, at every level, so a test can
 * assert on what reached the log. Close it in {@code @AfterEach}; closing detaches the appender and
 * restores the logger's level, so no other test sees either.
 *
 * <p>The level is lowered to TRACE while attached: a value logged only at DEBUG still reaches the
 * log in the dev profile, so a test that asserts something is never logged must see DEBUG too.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level originalLevel;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(Class<?> loggingClass) {
        this.logger = (Logger) LoggerFactory.getLogger(loggingClass);
        this.originalLevel = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.TRACE);
    }

    /**
     * Starts recording the events of {@code loggingClass}'s logger.
     *
     * @param loggingClass the class whose {@code @Slf4j} logger to record
     * @return the capture; close it to detach
     */
    public static LogCapture of(Class<?> loggingClass) {
        return new LogCapture(loggingClass);
    }

    /**
     * Each recorded event that carries {@code text}, case-insensitively, in its formatted message or
     * in the message of any throwable attached to it, its causes and suppressed throwables included,
     * rendered as {@code LEVEL message} for a failure message.
     *
     * <p>An empty result also holds when the path logged nothing through this logger; to assert that
     * a value is never logged, use {@link #assertNeverLogged(String)}, which refuses that case.
     *
     * @param text the value to look for
     * @return the matching events, empty when none carried it
     */
    public List<String> eventsContaining(String text) {
        String needle = text.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (ILoggingEvent event : List.copyOf(appender.list)) {
            String rendered = event.getFormattedMessage() + throwableMessages(event.getThrowableProxy());
            if (rendered.toLowerCase(Locale.ROOT).contains(needle)) {
                matches.add(event.getLevel() + " " + rendered);
            }
        }
        return matches;
    }

    /**
     * The formatted message of each recorded event at exactly {@code level}, in the order logged.
     *
     * @param level the level to keep
     * @return the messages at that level, empty when none was logged at it
     */
    public List<String> messagesAt(Level level) {
        return List.copyOf(appender.list).stream()
                .filter(event -> event.getLevel().equals(level))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /**
     * The class name of the throwable attached to each recorded event at exactly {@code level}, in the
     * order logged; an event at that level with no throwable attached contributes nothing.
     *
     * @param level the level to keep
     * @return the attached throwables' class names, empty when no event at that level carried one
     */
    public List<String> throwableClassNamesAt(Level level) {
        return List.copyOf(appender.list).stream()
                .filter(event -> event.getLevel().equals(level))
                .map(ILoggingEvent::getThrowableProxy)
                .filter(throwable -> throwable != null)
                .map(IThrowableProxy::getClassName)
                .toList();
    }

    /**
     * Fails unless the path logged at least one event through this logger and none of them carries
     * {@code text}, as {@link #eventsContaining(String)} matches it. With nothing recorded the absence
     * proves nothing: the path may log through another logger, or not have run.
     *
     * @param text the value that must never have been logged
     * @throws AssertionError when nothing was recorded, or when a recorded event carries {@code text}
     */
    public void assertNeverLogged(String text) {
        if (eventCount() == 0) {
            throw new AssertionError("nothing was logged through " + logger.getName()
                    + ", so the absence of \"" + text + "\" proves nothing");
        }
        List<String> carrying = eventsContaining(text);
        if (!carrying.isEmpty()) {
            throw new AssertionError("\"" + text + "\" must never be logged, but these events carry it: " + carrying);
        }
    }

    /** How many events were recorded, so a test can show its path logged anything at all. */
    public int eventCount() {
        return appender.list.size();
    }

    private static String throwableMessages(IThrowableProxy throwable) {
        StringBuilder messages = new StringBuilder();
        appendMessages(throwable, messages, Collections.newSetFromMap(new IdentityHashMap<>()));
        return messages.toString();
    }

    // A value can hide in a suppressed throwable (a close() failure, a second attempt) as well as a cause.
    // Logback already ends a cycle when it builds the proxy; the identity set only skips one met twice.
    private static void appendMessages(IThrowableProxy t, StringBuilder messages, Set<IThrowableProxy> seen) {
        if (t == null || !seen.add(t)) {
            return;
        }
        messages.append(" | ").append(t.getMessage());
        for (IThrowableProxy suppressed : t.getSuppressed()) {
            appendMessages(suppressed, messages, seen);
        }
        appendMessages(t.getCause(), messages, seen);
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
        appender.stop();
    }
}
