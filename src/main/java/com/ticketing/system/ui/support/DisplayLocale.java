package com.ticketing.system.ui.support;

import java.util.Locale;

/**
 * The locale the pages format dates, times and numbers, and change the case of text, in.
 *
 * <p>The pages are written in English and say so ({@code lang="en"} on {@code index.html}), so a month,
 * weekday, AM/PM marker or zone name in them must be English too, and so must a number's digits, grouping
 * and decimal mark, and a letter whose case changes. A {@code DateTimeFormatter}, {@code String.format} or
 * {@code toUpperCase} given no locale takes the server JVM's default instead: on a server set to German an
 * English page showed "Okt." and "$1.234,50", and on one set to Turkish "i" upper-cases to "İ" (#189). Every
 * formatter and case change in {@code ui} names a locale: {@link #PAGE} for text a page shows,
 * {@code Locale.ROOT} for machine text (a switch key, a CSS value, a JSON escape). A few accessible names
 * built from {@code %s} and an ungrouped {@code %d} take {@code Locale.ROOT} too, which writes them as
 * {@link #PAGE} would. {@code HexagonalRulesTest} refuses a call that names none. Vaadin's own components, the date
 * pickers among them, take the UI's locale instead, which {@link PageLocaleBootstrap} sets to {@link #PAGE}.
 */
public final class DisplayLocale {

    /** The pages' language, the one {@code lang="en"} declares. */
    public static final Locale PAGE = Locale.ENGLISH;

    private DisplayLocale() {
    }
}
