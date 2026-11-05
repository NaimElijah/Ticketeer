package com.ticketing.system.shared.exception;

/**
 * Thrown when a buyer checks out an order that is already frozen in checkout (UC-10, II.2.8;
 * NaimElijah/Ticketeer#240): by an attempt still running, as when a member pays from a second device while the
 * first is paying, or by one that was interrupted, as a restart during the payment leaves it. An ordinary refusal:
 * the buyer reads its message, and checkout logs it at WARN, not ERROR. After an interrupted attempt, checkout also
 * rolls the order back, so the message no longer holds there (NaimElijah/Ticketeer#214).
 */
public class CheckoutInProgressException extends DomainException {

    /** Creates the refusal, with the message the buyer reads. */
    public CheckoutInProgressException() {
        super("This order is already being paid for");
    }
}
