package com.ticketing.system.unit.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.CoreConstants;

/**
 * The log is read line by line, so a CR or LF inside a logged value would start a line the
 * application never wrote (#185). Every appender formats with one pattern, the {@code LOG_PATTERN}
 * property of {@code logback-spring.xml}, and that pattern keeps each event on one line whatever
 * its message holds.
 */
class LogbackPatternTest {

    private static final String LOGBACK_CONFIG = "/logback-spring.xml";
    private static final String PATTERN_PROPERTY = "LOG_PATTERN";
    private static final String PATTERN_REFERENCE = "${" + PATTERN_PROPERTY + "}";

    @Test
    void GivenLogbackConfig_WhenReadingEveryAppendersPattern_ThenEachUsesTheSharedLogPattern() throws Exception {
        Document config = readLogbackConfig();
        NodeList appenders = config.getElementsByTagName("appender");
        assertTrue(appenders.getLength() > 0, "logback-spring.xml declares no appender");

        List<String> offPattern = new ArrayList<>();
        for (int i = 0; i < appenders.getLength(); i++) {
            Element appender = (Element) appenders.item(i);
            NodeList patterns = appender.getElementsByTagName("pattern");
            String pattern = patterns.getLength() == 0 ? "<none>" : patterns.item(0).getTextContent().trim();
            if (!PATTERN_REFERENCE.equals(pattern)) {
                offPattern.add(appender.getAttribute("name") + "=" + pattern);
            }
        }

        assertEquals(List.of(), offPattern, "every appender must format with " + PATTERN_REFERENCE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    void GivenMessageWithLineBreak_WhenFormattedWithLogPattern_ThenTheEventIsOneLine(String lineBreak)
            throws Exception {
        PatternLayout layout = layoutOf(logPattern());
        Logger logger = ((LoggerContext) layout.getContext()).getLogger("forging.test");
        LoggingEvent event = new LoggingEvent(Logger.class.getName(), logger, Level.INFO,
                "sign-in failed for username={}", null, new Object[] {"mallory" + lineBreak + "INFO forged"});

        String formatted = layout.doLayout(event);

        assertTrue(formatted.endsWith(CoreConstants.LINE_SEPARATOR),
                "each event must end with one line separator, but was: " + escaped(formatted));
        String body = formatted.substring(0, formatted.length() - CoreConstants.LINE_SEPARATOR.length());
        assertFalse(body.contains("\n") || body.contains("\r"),
                "a line break in the message must not start a new log line, but the event was: " + escaped(formatted));
    }

    private static String logPattern() throws Exception {
        NodeList properties = readLogbackConfig().getElementsByTagName("property");
        for (int i = 0; i < properties.getLength(); i++) {
            Element property = (Element) properties.item(i);
            if (PATTERN_PROPERTY.equals(property.getAttribute("name"))) {
                String value = property.getAttribute("value");
                assertFalse(value.isBlank(), PATTERN_PROPERTY + " must carry its pattern in a value attribute");
                return value;
            }
        }
        throw new AssertionError("logback-spring.xml defines no <property name=\"" + PATTERN_PROPERTY + "\">");
    }

    private static PatternLayout layoutOf(String pattern) {
        LoggerContext context = new LoggerContext();
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern(pattern);
        layout.start();
        return layout;
    }

    private static Document readLogbackConfig() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (InputStream xml = LogbackPatternTest.class.getResourceAsStream(LOGBACK_CONFIG)) {
            assertNotNull(xml, LOGBACK_CONFIG + " is not on the classpath");
            return factory.newDocumentBuilder().parse(xml);
        }
    }

    private static String escaped(String text) {
        return text.replace("\r", "\\r").replace("\n", "\\n");
    }

    // ---- Defect 12: a line break in a logged exception does not start a new log line (#187) ----
    // An exception's message can carry text a user typed, as a logged value can, and the stack trace
    // is printed on lines of its own unless the pattern renders it. The exception is still logged.

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    void GivenExceptionWhoseMessageHasALineBreak_WhenFormattedWithLogPattern_ThenTheEventIsOneLine(String lineBreak)
            throws Exception {
        PatternLayout layout = layoutOf(logPattern());
        Logger logger = ((LoggerContext) layout.getContext()).getLogger("forging.test");
        IllegalStateException failure = new IllegalStateException("lookup failed for mallory" + lineBreak + "INFO forged");
        LoggingEvent event = new LoggingEvent(Logger.class.getName(), logger, Level.WARN,
                "request failed", failure, null);

        String formatted = layout.doLayout(event);

        assertTrue(formatted.endsWith(CoreConstants.LINE_SEPARATOR),
                "each event must end with one line separator, but was: " + escaped(formatted));
        String body = formatted.substring(0, formatted.length() - CoreConstants.LINE_SEPARATOR.length());
        assertFalse(body.contains("\n") || body.contains("\r"),
                "a line break in a logged exception must not start a new log line, but the event was: "
                        + escaped(formatted));
        assertTrue(body.contains(IllegalStateException.class.getName()) && body.contains("INFO forged"),
                "the exception must still be logged, on the event's line, but the event was: " + escaped(formatted));
    }

    // The exception follows the message after one " | ", and the trace's last line break leaves no
    // separator behind; an event without an exception ends at its message.
    @Test
    void GivenEventsWithAndWithoutAnException_WhenFormattedWithLogPattern_ThenOnlyTheExceptionFollowsTheMessageAfterOneSeparator()
            throws Exception {
        PatternLayout layout = layoutOf(logPattern());
        Logger logger = ((LoggerContext) layout.getContext()).getLogger("separator.test");
        String message = "request failed";
        String separator = " | ";
        LoggingEvent withException = new LoggingEvent(Logger.class.getName(), logger, Level.WARN,
                message, new IllegalStateException("lookup failed"), null);
        LoggingEvent withoutException = new LoggingEvent(Logger.class.getName(), logger, Level.WARN,
                message, null, null);

        String formattedWithException = layout.doLayout(withException);
        String formattedWithoutException = layout.doLayout(withoutException);

        assertTrue(formattedWithException.contains(message + separator + IllegalStateException.class.getName()),
                "the exception must follow the message after one separator, but the event was: "
                        + escaped(formattedWithException));
        assertFalse(formattedWithException.endsWith(separator + CoreConstants.LINE_SEPARATOR),
                "no separator may trail the exception, but the event was: " + escaped(formattedWithException));
        assertTrue(formattedWithoutException.endsWith(message + CoreConstants.LINE_SEPARATOR),
                "nothing but the line separator may follow the message of an event without an exception, but the"
                        + " event was: " + escaped(formattedWithoutException));
    }
}
