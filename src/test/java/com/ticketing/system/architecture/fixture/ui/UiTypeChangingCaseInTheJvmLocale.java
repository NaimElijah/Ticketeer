package com.ticketing.system.architecture.fixture.ui;

/** Offender: UI text changed to lower and upper case by the server's rules, which in Turkish dot an "i". */
public class UiTypeChangingCaseInTheJvmLocale {

    String label(String name) {
        return name.toLowerCase() + name.toUpperCase();
    }
}
