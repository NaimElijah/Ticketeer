package com.ticketing.system.catalog.domain;

/**
 * Canonical seat-row label helpers.
 *
 * <p>Row labels use bijective base-26 (spreadsheet-column) encoding so that venues with more than
 * 26 rows still get clean, letter-only labels: index {@code 0 -> "A"}, {@code 25 -> "Z"},
 * {@code 26 -> "AA"}, {@code 27 -> "AB"}, ... This matches the label contract the backend already
 * relies on — {@code EventManagementService} parses the leading non-digit run of a seat label as its
 * row, explicitly anticipating multi-character rows like {@code "AA12"}.
 */
public final class SeatLabels {

    private SeatLabels() {
    }

    /**
     * Returns the bijective base-26 row label for a 0-based row index.
     *
     * @param zeroBasedRowIndex the row index (0 == first row)
     * @return the row label (e.g. 0 -> "A", 26 -> "AA")
     * @throws IllegalArgumentException if the index is negative
     */
    public static String rowLabel(int zeroBasedRowIndex) {
        if (zeroBasedRowIndex < 0) {
            throw new IllegalArgumentException("row index must be >= 0: " + zeroBasedRowIndex);
        }
        StringBuilder sb = new StringBuilder();
        for (int n = zeroBasedRowIndex; n >= 0; n = n / 26 - 1) {
            sb.insert(0, (char) ('A' + n % 26));
        }
        return sb.toString();
    }
}
