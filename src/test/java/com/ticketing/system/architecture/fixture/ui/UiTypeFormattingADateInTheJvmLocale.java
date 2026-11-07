package com.ticketing.system.architecture.fixture.ui;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** Offender: a UI date formatter built without a locale, so it names months in the server's language. */
public class UiTypeFormattingADateInTheJvmLocale {

    String format(LocalDate date) {
        return DateTimeFormatter.ofPattern("d MMM yyyy").format(date);
    }
}
