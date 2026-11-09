package com.ticketing.system.ui.components;

import com.ticketing.system.ui.support.DisplayLocale;

/**
 * Conversion between dollar amounts and whole cents, and the dollar text the pages show for cents.
 */
public final class Money {

    private Money() { }

    /**
     * Converts dollars to cents.
     *
     * @param dollars an amount in dollars
     * @return the amount in whole cents, rounded to the nearest cent
     */
    public static long toCents(double dollars) {
        return Math.round(dollars * 100);
    }

    /**
     * Formats an amount of cents as dollar text, such as {@code "$12.34"}. The amount has no
     * thousands separator, and a negative amount reads {@code "-$12.34"}.
     *
     * @param cents the amount in cents
     * @return the dollar text
     */
    public static String format(long cents) {
        long abs = Math.abs(cents);
        return (cents < 0 ? "-" : "") + "$" + (abs / 100) + "." + String.format(DisplayLocale.PAGE, "%02d", abs % 100);
    }
}