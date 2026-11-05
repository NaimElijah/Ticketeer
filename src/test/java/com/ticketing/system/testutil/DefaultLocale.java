package com.ticketing.system.testutil;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Test helper: runs a call under another JVM default locale, then puts the default and both of its categories
 * back. {@link Locale#setDefault(Locale)} also resets the DISPLAY and FORMAT categories, which can differ from
 * the default (a Windows machine set to English with German formats), so restoring only the default would
 * change them for every test that runs after it in the same JVM.
 */
public final class DefaultLocale {

    private DefaultLocale() {
    }

    /**
     * Calls {@code call} with {@code locale} as the default in every category.
     *
     * @return what {@code call} returns
     */
    public static <T> T callUnder(Locale locale, Supplier<T> call) {
        Locale defaultLocale = Locale.getDefault();
        Locale displayLocale = Locale.getDefault(Locale.Category.DISPLAY);
        Locale formatLocale = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(locale);
        try {
            return call.get();
        } finally {
            Locale.setDefault(defaultLocale);
            Locale.setDefault(Locale.Category.DISPLAY, displayLocale);
            Locale.setDefault(Locale.Category.FORMAT, formatLocale);
        }
    }

    /** Runs {@code action} with {@code locale} as the default in every category. */
    public static void runUnder(Locale locale, Runnable action) {
        callUnder(locale, () -> {
            action.run();
            return null;
        });
    }
}
