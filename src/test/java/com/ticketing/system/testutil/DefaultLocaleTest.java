package com.ticketing.system.testutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link DefaultLocale} puts back the default and each category it had, even when the call fails. */
class DefaultLocaleTest {

    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");
    // A format locale unlike any JVM default, so a reset to the default shows.
    private static final Locale ICELANDIC_FORMAT = Locale.forLanguageTag("is-IS");

    private Locale defaultBefore;
    private Locale displayBefore;
    private Locale formatBefore;

    @BeforeEach
    void setUp() {
        defaultBefore = Locale.getDefault();
        displayBefore = Locale.getDefault(Locale.Category.DISPLAY);
        formatBefore = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, ICELANDIC_FORMAT);
    }

    @AfterEach
    void tearDown() {
        Locale.setDefault(defaultBefore);
        Locale.setDefault(Locale.Category.DISPLAY, displayBefore);
        Locale.setDefault(Locale.Category.FORMAT, formatBefore);
    }

    @Test
    void GivenAFormatLocaleUnlikeTheDefault_WhenACallRunsUnderAnotherLocale_ThenEachCategoryIsPutBack() {
        Locale formatInside = DefaultLocale.callUnder(TURKISH, () -> Locale.getDefault(Locale.Category.FORMAT));

        assertEquals(TURKISH, formatInside, "the call runs with the locale in every category");
        assertEquals(defaultBefore, Locale.getDefault());
        assertEquals(displayBefore, Locale.getDefault(Locale.Category.DISPLAY));
        assertEquals(ICELANDIC_FORMAT, Locale.getDefault(Locale.Category.FORMAT),
            "the format category must come back as it was, not follow the default");
    }

    @Test
    void GivenAnActionThatThrows_WhenItRunsUnderAnotherLocale_ThenEachCategoryIsStillPutBack() {
        assertThrows(IllegalStateException.class, () -> DefaultLocale.runUnder(TURKISH, () -> {
            throw new IllegalStateException("the action failed");
        }));

        assertEquals(defaultBefore, Locale.getDefault());
        assertEquals(ICELANDIC_FORMAT, Locale.getDefault(Locale.Category.FORMAT));
    }
}
