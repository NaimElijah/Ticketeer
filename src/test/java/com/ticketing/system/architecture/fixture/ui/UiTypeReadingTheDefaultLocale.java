package com.ticketing.system.architecture.fixture.ui;

import java.util.Locale;

/** Offender: a UI class reading the server's locale, to format in it. */
public class UiTypeReadingTheDefaultLocale {

    Locale[] locales() {
        return new Locale[] {Locale.getDefault(), Locale.getDefault(Locale.Category.FORMAT)};
    }
}
