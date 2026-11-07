package com.ticketing.system.architecture.fixture.ui;

/** Offender: a UI count formatted with {@code formatted}, which always takes the server's locale. */
public class UiTypeFormattingWithFormatted {

    String format(int count) {
        return "%,d".formatted(count);
    }
}
