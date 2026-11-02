package com.ticketing.system.ui.presenters.company;

import com.ticketing.system.shared.exception.RefundFailedException;
import com.ticketing.system.shared.exception.RefundGatewayUnreachableException;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * How an event cancel's refund failure reads to the owner, for both cancel presenters
 * ({@link CompanyEventListPresenter}, {@link EventManagementPresenter}): the event is cancelled before
 * its refunds run, so a refund failure is reported as such, not as a failed cancel (#187, UI-A3).
 */
final class CancelRefundFailure {

    private CancelRefundFailure() { }

    /**
     * @param failure the refund failure the cancel raised
     * @return the text shown after "some refunds failed"
     */
    static String reason(RefundFailedException failure) {
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
