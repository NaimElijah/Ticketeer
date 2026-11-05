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
     * Makes {@code locale} the default in every category until the returned swap is closed, for a swap that
     * spans a test's set-up and tear-down.
     *
     * @return the swap, whose {@code close} puts back the default and each category as they were
     */
    public static Swap swapTo(Locale locale) {
        Swap swap = new Swap();
        Locale.setDefault(locale);
        return swap;
    }

    /**
     * Calls {@code call} with {@code locale} as the default in every category.
     *
     * @return what {@code call} returns
     */
    public static <T> T callUnder(Locale locale, Supplier<T> call) {
        try (Swap swap = swapTo(locale)) {
            return call.get();
        }
    }

    /** Runs {@code action} with {@code locale} as the default in every category. */
    public static void runUnder(Locale locale, Runnable action) {
        callUnder(locale, () -> {
            action.run();
            return null;
        });
    }

    /** The default locale and its two categories as they were before a swap; closing it puts them back. */
    public static final class Swap implements AutoCloseable {

        private final Locale defaultLocale = Locale.getDefault();
        private final Locale displayLocale = Locale.getDefault(Locale.Category.DISPLAY);
        private final Locale formatLocale = Locale.getDefault(Locale.Category.FORMAT);

        private Swap() {
        }

        @Override
        public void close() {
            Locale.setDefault(defaultLocale);
            Locale.setDefault(Locale.Category.DISPLAY, displayLocale);
            Locale.setDefault(Locale.Category.FORMAT, formatLocale);
        }
    }
}
