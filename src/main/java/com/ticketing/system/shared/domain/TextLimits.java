package com.ticketing.system.shared.domain;

import com.ticketing.system.shared.exception.BusinessRuleViolationException;

/**
 * The length limit on text a user enters: names, descriptions, subjects and message bodies.
 *
 * <p>The domain refuses longer text with a message the user can act on. Without it, the Jpa
 * adapters fail the insert on the column and the UI shows generic text, while the Memory adapters
 * store the value, so the two backends drift (#186).
 */
public final class TextLimits {

    /** The width of the user-entered text columns, {@code varchar(255)} in {@code V1__baseline_schema.sql}. */
    public static final int MAX_TEXT_LENGTH = 255;

    private TextLimits() { }

    /**
     * Refuses {@code value} when it is longer than {@link #MAX_TEXT_LENGTH}.
     *
     * <p>A {@code null} value passes: whether a field may be absent is its owner's invariant.
     *
     * @param field the user-facing name of the field, e.g. {@code "Event name"}; it starts the message
     * @param value the text to check; never echoed in the message, which is logged
     * @throws BusinessRuleViolationException if {@code value} is longer than the limit
     */
    public static void requireFits(String field, String value) {
        requireAtMost(field, value, MAX_TEXT_LENGTH);
    }

    /**
     * Refuses {@code value} when it is longer than {@code limit}, for text that shares its column with
     * something appended to it.
     *
     * @param field the user-facing name of the field; it starts the message
     * @param value the text to check, or {@code null}, which passes
     * @param limit the most characters {@code value} may have
     * @throws BusinessRuleViolationException if {@code value} is longer than {@code limit}
     */
    public static void requireAtMost(String field, String value, int limit) {
        if (value != null && value.length() > limit) {
            throw new BusinessRuleViolationException(
                    field + " can be at most " + limit + " characters.");
        }
    }
}
