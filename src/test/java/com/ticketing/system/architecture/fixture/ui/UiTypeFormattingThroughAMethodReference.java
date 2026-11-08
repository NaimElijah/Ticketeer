package com.ticketing.system.architecture.fixture.ui;

import java.util.function.Function;
import java.util.function.UnaryOperator;

/** Offender: UI text formatted and upper-cased in the server's locale through method references, not calls. */
public class UiTypeFormattingThroughAMethodReference {

    Function<Object[], String> counter() {
        return "%,d"::formatted;
    }

    UnaryOperator<String> capitals() {
        return String::toUpperCase;
    }
}
