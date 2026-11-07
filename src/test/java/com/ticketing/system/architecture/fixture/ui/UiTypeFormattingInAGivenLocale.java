package com.ticketing.system.architecture.fixture.ui;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Locale;

/** Allowed case: UI dates, amounts and case changes in the locale they are given. */
public class UiTypeFormattingInAGivenLocale {

    String format(LocalDate date, double amount, String name) {
        return DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).format(date)
            + String.format(Locale.ENGLISH, "$%,.2f", amount)
            + new DateTimeFormatterBuilder().appendPattern("d MMM").toFormatter(Locale.ENGLISH).format(date)
            + NumberFormat.getInstance(Locale.ENGLISH).format(amount)
            + new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH)).format(amount)
            + new MessageFormat("{0} sold", Locale.ENGLISH).format(new Object[] {amount})
            + name.toUpperCase(Locale.ROOT);
    }
}
