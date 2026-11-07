package com.ticketing.system.architecture.fixture.ui;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

/** Offender: UI dates in the localized styles, which name months and order fields the server's way. */
public class UiTypeFormattingALocalizedDate {

    String format(LocalDateTime at) {
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(at)
            + DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(at)
            + DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).format(at);
    }
}
