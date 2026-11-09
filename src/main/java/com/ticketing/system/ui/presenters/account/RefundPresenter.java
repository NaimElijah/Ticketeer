package com.ticketing.system.ui.presenters.account;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.ticketing.system.shared.dto.RefundResultDTO;
import com.ticketing.system.sales.application.service.RefundService;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.EntityNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.RefundFailedException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * Presenter for the member "Request refund" action (#284), shared by {@code ReceiptView} and
 * {@code MyAccountView}. Vaadin-free; calls {@link RefundService} and returns a sealed
 * {@link Outcome} the view switches on. The view passes the member token
 * ({@code SessionIdentity.memberToken()}). An order that belongs to another account gives the same
 * {@link Outcome.NotFound} as a missing id, so a member cannot tell which order ids exist on other
 * accounts.
 */
@Slf4j
@Component
public class RefundPresenter {

    private static final String ORDER_NOT_FOUND_MESSAGE = "We couldn't find that order.";
    private static final String REFUND_FAILED_MESSAGE =
        "The refund could not be completed. If it does not appear on your account soon, contact support.";

    private final RefundService refundService;

    /** Creates the presenter over the refund service. */
    public RefundPresenter(RefundService refundService) {
        this.refundService = refundService;
    }

    /**
     * Requests a refund of one of the signed-in member's orders.
     *
     * @param token   the member's token
     * @param orderId the order to refund
     * @param reason  the member's stated reason, passed to the service as given
     * @return {@link Outcome.NotEligible} when the service refuses the order as ineligible;
     *     {@link Outcome.NotFound} when no such order exists for this member; {@link Outcome.Failure}
     *     with fixed text when the token is rejected or the refund fails, and with
     *     {@code ServiceErrors}' text for any other failure
     */
    public Outcome requestRefund(String token, int orderId, String reason) {
        try {
            RefundResultDTO result = refundService.requestRefund(token, orderId, reason);
            return new Outcome.Success(result.refundTransactionId(), result.totalRefunded());
        } catch (BusinessRuleViolationException e) {
            return new Outcome.NotEligible("This order isn't eligible for a refund.");
        } catch (EntityNotFoundException | UnauthorizedActionException e) {
            return new Outcome.NotFound(ORDER_NOT_FOUND_MESSAGE);
        } catch (InvalidTokenException e) {
            return new Outcome.Failure("Please sign in to request a refund.");
        } catch (RefundFailedException e) {
            // A refund failure carries the payment gateway's operator wording and the charge it names, so the
            // member reads fixed text. It is a DomainException, but not a refusal: the member's money was
            // not returned, or nobody knows whether it was, so it is logged at WARN as a degraded state,
            // with its exception, rather than at ServiceErrors' DEBUG for refusals (#187).
            log.warn("Refund failed at the payment gateway action=\"request refund orderId={}\"", orderId, e);
            return new Outcome.Failure(REFUND_FAILED_MESSAGE);
        } catch (RuntimeException e) {
            return new Outcome.Failure(ServiceErrors.userMessageLogged(e, "request refund orderId=" + orderId));
        }
    }

    /** Sealed outcome the view switches on after a refund attempt. */
    public sealed interface Outcome {
        /**
         * The service accepted the request.
         *
         * @param reference the refund's transaction id, as the service returned it
         * @param amount    the total refunded, as the service returned it
         */
        record Success(String reference, double amount) implements Outcome { }

        /**
         * The service refused the order as ineligible ({@code BusinessRuleViolationException}).
         *
         * @param message fixed text for the user
         */
        record NotEligible(String message) implements Outcome { }

        /**
         * No such order exists for this member.
         *
         * @param message fixed text for the user
         */
        record NotFound(String message)    implements Outcome { }

        /**
         * The member has to sign in, the refund failed, or the call failed in some other way.
         *
         * @param message the text the user may read
         */
        record Failure(String message)     implements Outcome { }
    }
}
