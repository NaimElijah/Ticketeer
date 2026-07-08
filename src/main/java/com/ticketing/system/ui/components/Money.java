package com.ticketing.system.ui.components;


public final class Money {

    private Money() { }

    public static long toCents(double dollars) {
        return Math.round(dollars * 100);
    }

    public static String format(long cents) {
        long abs = Math.abs(cents);
        return (cents < 0 ? "-" : "") + "$" + (abs / 100) + "." + String.format("%02d", abs % 100);
    }
}