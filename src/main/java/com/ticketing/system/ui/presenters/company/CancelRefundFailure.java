package com.ticketing.system.ui.presenters.company;

import com.ticketing.system.shared.exception.RefundFailedException;
import com.ticketing.system.shared.exception.RefundGatewayUnreachableException;
import com.ticketing.system.shared.exception.RefundOutcomeUnknownException;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * How an event cancel's refund failure reads to the owner, for both cancel presenters
 * ({@link CompanyEventListPresenter}, {@link EventManagementPresenter}): the event is cancelled before
 * its refunds run, so a refund failure is reported as such, not as a failed cancel (#187, UI-A3).
 */
final class CancelRefundFailure {

    static final String GATEWAY_UNREACHABLE = "The payment service could not be reached.";
    static final String OUTCOME_UNKNOWN =
        "The payment service did not confirm them, so they need to be checked before any retry.";

    private CancelRefundFailure() { }

    /**
     * The text shown after "some refunds failed". A failure the payment gateway raised carries the
     * gateway's own operator wording and the charge it names, so it reads as fixed text instead (#187).
     * The plain {@link RefundFailedException} that reaches a cancel is the refund service's own summary,
     * naming the receipts it could not refund ({@code EventCancellationRefundService}), and is shown as is.
     *
     * @param failure the refund failure the cancel raised
     * @return the reason to show the owner
     */
    static String reason(RefundFailedException failure) {
        if (failure instanceof RefundGatewayUnreachableException) {
            return GATEWAY_UNREACHABLE;
        }
        if (failure instanceof RefundOutcomeUnknownException) {
            return OUTCOME_UNKNOWN;
        }
        return ServiceErrors.userMessage(failure);
    }

    /**
     * @param failure the refund failure the cancel raised
     * @return {@code true} only when cancelling again retries the refund: the payment gateway could not
     *     be reached, so no money moved and the claim was released
     */
    static boolean cancelAgainRetries(RefundFailedException failure) {
        return failure instanceof RefundGatewayUnreachableException;
    }
}
