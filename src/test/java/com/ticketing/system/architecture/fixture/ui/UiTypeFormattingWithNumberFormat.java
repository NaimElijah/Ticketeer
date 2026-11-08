package com.ticketing.system.architecture.fixture.ui;

import java.text.NumberFormat;

/** Offender: UI numbers formatted by the number formats of the server's locale. */
public class UiTypeFormattingWithNumberFormat {

    String format(double amount) {
        return NumberFormat.getInstance().format(amount)
            + NumberFormat.getNumberInstance().format(amount)
            + NumberFormat.getIntegerInstance().format(amount)
            + NumberFormat.getCurrencyInstance().format(amount)
            + NumberFormat.getPercentInstance().format(amount)
            + NumberFormat.getCompactNumberInstance().format(amount);
    }
}
