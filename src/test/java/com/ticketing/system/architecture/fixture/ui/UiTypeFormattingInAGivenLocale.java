package com.ticketing.system.architecture.fixture.ui;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Allowed case: a UI date and amount formatted in the locale they are given. */
public class UiTypeFormattingInAGivenLocale {

    String format(LocalDate date, double amount) {
        return DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).format(date)
            + String.format(Locale.ENGLISH, "$%,.2f", amount);
    }
}
