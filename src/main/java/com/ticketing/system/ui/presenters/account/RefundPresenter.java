package com.ticketing.system.ui.presenters.account;

import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.RefundResultDTO;
import com.ticketing.system.sales.application.service.RefundService;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.EntityNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;

import lombok.extern.slf4j.Slf4j;

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

    private final RefundService refundService;

    public RefundPresenter(RefundService refundService) {
        this.refundService = refundService;
    }

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
        } catch (RuntimeException e) {
            log.warn("Refund failed for order {}: {}", orderId, e.getMessage());
            return new Outcome.Failure("We couldn't process this refund right now.");
        }
    }

    /** Sealed outcome the view switches on after a refund attempt. */
    public sealed interface Outcome {
        record Success(String reference, double amount) implements Outcome { }
        record NotEligible(String message) implements Outcome { }
        record NotFound(String message)    implements Outcome { }
        record Failure(String message)     implements Outcome { }
    }
}
