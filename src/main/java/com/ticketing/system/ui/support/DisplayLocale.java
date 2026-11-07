package com.ticketing.system.ui.support;

import java.util.Locale;

/**
 * The locale the pages format dates and times in.
 *
 * <p>The pages are written in English and say so ({@code lang="en"} on {@code index.html}), so a month,
 * weekday, AM/PM marker or zone name in them must be English too. A {@code DateTimeFormatter} built without
 * a locale takes the server JVM's default instead, and on a server set to German an English page showed
 * "Okt." (#189). Every formatter in {@code ui} with a text field is built with {@link #PAGE}.
 */
public final class DisplayLocale {

    /** The pages' language, the one {@code lang="en"} declares. */
    public static final Locale PAGE = Locale.ENGLISH;

    private DisplayLocale() {
    }
}
