package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Every page declares its language, English, so a screen reader reads it with the right voice and
 * pronunciation (#188, A-M9, U-A9, P-V5; WCAG 3.1.1, level A).
 *
 * <p>Vaadin serves every route from the one bootstrap page {@code src/main/frontend/index.html}, and
 * {@code AppShellSettings} in Flow 24.10.7 has no setter for the root element's {@code lang}, so the page's
 * {@code <html>} start tag is where the language is declared. The test reads that file from the project
 * directory, where Maven runs the tests. A fix that sets {@code lang} at serve time instead (an
 * {@code IndexHtmlRequestListener}) would need this test to run that listener.
 */
class PageLanguageTest {

    // ---- F4: page language (#188) ----

    private static final Path BOOTSTRAP_PAGE = Path.of("src", "main", "frontend", "index.html");
    private static final Pattern HTML_START_TAG = Pattern.compile("<html\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LANG_EN = Pattern.compile("\\blang\\s*=\\s*[\"']en(-[A-Za-z]+)?[\"']",
        Pattern.CASE_INSENSITIVE);
    // A comment above the root element may quote "<html>"; only the real start tag counts.
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    @Test
    void GivenTheBootstrapPage_WhenItIsServed_ThenItsHtmlElementDeclaresEnglish() throws IOException {
        String page = HTML_COMMENT.matcher(Files.readString(BOOTSTRAP_PAGE, StandardCharsets.UTF_8)).replaceAll("");

        Matcher html = HTML_START_TAG.matcher(page);
        assertTrue(html.find(), "the bootstrap page has no <html> start tag");
        assertTrue(LANG_EN.matcher(html.group()).find(),
            "the page must declare its language: expected lang=\"en\" on " + html.group());
    }
}
