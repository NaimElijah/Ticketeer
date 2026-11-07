package com.ticketing.system.architecture.fixture.ui;

/** Offender: a UI amount formatted without a locale, so it groups and marks decimals the server's way. */
public class UiTypeFormattingWithStringFormat {

    String format(double amount) {
        return String.format("$%,.2f", amount);
    }
}
