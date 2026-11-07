package com.ticketing.system.ui.support;

import java.util.Locale;

/**
 * The locale the pages format dates, times and numbers in.
 *
 * <p>The pages are written in English and say so ({@code lang="en"} on {@code index.html}), so a month,
 * weekday, AM/PM marker or zone name in them must be English too, and so must a number's digits, grouping
 * and decimal mark. A {@code DateTimeFormatter} or {@code String.format} given no locale takes the server
 * JVM's default instead: on a server set to German an English page showed "Okt." and "$1.234,50" (#189).
 * Every {@code DateTimeFormatter.ofPattern} and {@code String.format} in {@code ui} is given {@link #PAGE}.
 */
public final class DisplayLocale {

    /** The pages' language, the one {@code lang="en"} declares. */
    public static final Locale PAGE = Locale.ENGLISH;

    private DisplayLocale() {
    }
}
