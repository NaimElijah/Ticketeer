package com.ticketing.system.shared.exception;

/**
 * The one rule for which failure text a user may read, wherever that text is composed: in the UI
 * ({@code ui/support/ServiceErrors}) and in a backend context that puts a failure's reason into a
 * member's notification ({@code ReservationService}). Kept here so the copies cannot drift when the
 * rule narrows (NaimElijah/Ticketeer#219).
 *
 * <p>Business rules are signalled with {@link DomainException} and, in much of the code, with
 * {@link IllegalArgumentException} / {@link IllegalStateException}, whose messages are written for the
 * user. Any other exception's message is written for operators, so a user reads
 * {@link #GENERIC_MESSAGE} in its place.
 */
public final class UserFacingErrors {

    /** What a user reads for a failure whose own text is not user-facing. */
    public static final String GENERIC_MESSAGE = "Something went wrong — please try again.";

    private UserFacingErrors() { }

    /**
     * Whether a user may read {@code failure}'s own message.
     *
     * @param failure the exception a call threw; not {@code null}
     * @return {@code true} for a {@link DomainException}, {@link IllegalArgumentException} or
     *         {@link IllegalStateException} whose message is not null or blank; {@code false} otherwise
     */
    public static boolean isUserFacing(RuntimeException failure) {
        boolean ruleType = failure instanceof DomainException
                || failure instanceof IllegalArgumentException
                || failure instanceof IllegalStateException;
        String message = failure.getMessage();
        return ruleType && message != null && !message.isBlank();
    }
}
