package com.ticketing.system.architecture.fixture.ui;

import java.text.DecimalFormat;

/** Offender: UI amounts formatted by decimal formats given no symbols, so they take the server's. */
public class UiTypeFormattingWithDecimalFormat {

    String format(double amount) {
        return new DecimalFormat("#,##0.00").format(amount) + new DecimalFormat().format(amount);
    }
}
