package com.ticketing.system.architecture.fixture.ui;

import java.text.MessageFormat;

/** Offender: UI counts formatted by message formats given no locale, so they group the server's way. */
public class UiTypeFormattingWithMessageFormat {

    String format(int count) {
        return new MessageFormat("{0} sold").format(new Object[] {count})
            + MessageFormat.format("{0} left", count);
    }
}
