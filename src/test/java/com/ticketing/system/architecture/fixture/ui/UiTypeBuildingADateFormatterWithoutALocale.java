package com.ticketing.system.architecture.fixture.ui;

import java.time.LocalDate;
import java.time.format.DateTimeFormatterBuilder;

/** Offender: a UI date formatter built with no locale, so it names months in the server's language. */
public class UiTypeBuildingADateFormatterWithoutALocale {

    String format(LocalDate date) {
        return new DateTimeFormatterBuilder().appendPattern("d MMM yyyy").toFormatter().format(date);
    }
}
