package com.ticketing.system.ui.presenters;

import java.util.List;

/**
 * The result of a presenter's list read: the items, or the reason the read failed. A failed read is
 * never an empty list, so a view cannot show "nothing here" for a store that did not answer.
 *
 * @param <T> the type of the listed items
 */
public sealed interface ListOutcome<T> {

    /**
     * The read succeeded.
     *
     * @param items the items read, possibly none; never {@code null}
     */
    record Loaded<T>(List<T> items) implements ListOutcome<T> {
        public Loaded {
            items = List.copyOf(items);
        }
    }

    /**
     * The read failed.
     *
     * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
     */
    record Failure<T>(String reason) implements ListOutcome<T> { }
}
